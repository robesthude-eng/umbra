// Package gc очищает осиротевшие блобы: файлы/объекты в blobstore, метаданных
// которых нет (или уже нет) в хранилище. Такая ситуация возможна после
// аварийного завершения между записью blob и сохранением метаданных, либо
// после удаления метаданных (например, при «сжигании» аккаунта).
//
// Льготный период (minAge): загрузка публикует blob раньше, чем фиксируется
// строка метаданных, поэтому GC, запущенный параллельно с загрузкой, не должен
// удалять свежие блобы — иначе медиа «пропадёт» между Put и SaveMedia.
// Сироты младше minAge пропускаются и будут удалены следующим запуском.
package gc

import (
	"context"
	"errors"
	"fmt"
	"log"
	"time"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/store"
)

// DefaultMinAge — льготный период по умолчанию: сутки. За это время любая
// легитимная загрузка гарантированно фиксирует метаданные, а настоящий сирота
// дожидается следующего запуска GC.
const DefaultMinAge = 24 * time.Hour

// Stats — итог одного прохода GC.
type Stats struct {
	Listed  int // перечислено блобов
	Removed int // удалено сирот
	Skipped int // пропущено свежих сирот (льготный период)
	Errors  int // пропущено из-за ошибок (чтение метаданных, удаление)
}

// RemoveOrphans перечисляет блобы и удаляет тех, чьи метаданные не найдены
// и чей возраст не меньше minAge. now передаётся явно — для детерминизма
// тестов; в продакшене это time.Now(). minAge <= 0 отключает льготный
// период (удаляются все сироты независимо от возраста).
//
// Blobs с неизвестным возрастом (нулевое ModTime) считаются достаточно
// старыми: интерфейс Lister обязаны заполнять ModTime обе реализации,
// а нуль означает лишь гонку с уже исчезнувшим файлом.
func RemoveOrphans(ctx context.Context, st store.Store, blobs blobstore.BlobStore, minAge time.Duration, now time.Time) (Stats, error) {
	lister, ok := blobs.(blobstore.Lister)
	if !ok {
		return Stats{}, errors.New("blobstore не поддерживает перечисление")
	}
	infos, err := lister.List()
	if err != nil {
		return Stats{}, fmt.Errorf("перечисление блобов: %w", err)
	}
	stats := Stats{Listed: len(infos)}
	for _, info := range infos {
		if _, err := st.GetMedia(ctx, info.ID); err == nil {
			continue // есть метаданные — не сирота
		} else if !errors.Is(err, store.ErrNotFound) {
			log.Printf("пропуск %s: %v", info.ID, err)
			stats.Errors++
			continue
		}
		// Сирота. Свежую не удаляем: метаданные загрузки могут ещё не быть зафиксированы.
		if minAge > 0 && !info.ModTime.IsZero() && now.Sub(info.ModTime) < minAge {
			stats.Skipped++
			continue
		}
		if err := blobs.Delete(info.ID); err != nil {
			if errors.Is(err, store.ErrNotFound) {
				continue // файло исчез между List и Delete — удалять нечего
			}
			log.Printf("не удалось удалить %s: %v", info.ID, err)
			stats.Errors++
			continue
		}
		stats.Removed++
		log.Printf("удалён осиротевший blob: %s", info.ID)
	}
	return stats, nil
}
