// Package metrics — минимальный реестр метрик с выводом в Prometheus text format.
//
// Зачем своё, а не client_golang: в go.mod сейчас только pgx и ws-стек, и тянуть
// большую зависимость ради десятка счётчиков несоразмерно (больше кода в сборке,
// больше поверхность для govulncheck). Формат вывода совместим, поэтому замена на
// полноценную библиотеку позже не ломает дашборды.
//
// Лейблы передаются парами key, value. Значения с высокой кардинальностью
// (телефоны, идентификаторы, сырые URL) передавать нельзя.
package metrics

import (
	"fmt"
	"io"
	"sort"
	"strconv"
	"strings"
	"sync"
)

// Buckets гистограмм длительности в секундах.
var durationBuckets = []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10}

type series struct {
	name   string
	help   string
	kind   string // counter | gauge | histogram
	labels string
	value  float64
	count  uint64
	sum    float64
	bucket []uint64
}

var (
	mu   sync.Mutex
	data = map[string]*series{}
)

func key(name, labels string) string { return name + "\x00" + labels }

// renderLabels собирает отсортированный набор лейблов: порядок аргументов не должен
// порождать разные временные ряды.
func renderLabels(kv []string) string {
	if len(kv) == 0 {
		return ""
	}
	if len(kv)%2 != 0 {
		kv = append(kv[:len(kv):len(kv)], "")
	}
	pairs := make([]string, 0, len(kv)/2)
	for i := 0; i < len(kv); i += 2 {
		pairs = append(pairs, kv[i]+"=\""+escape(kv[i+1])+"\"")
	}
	sort.Strings(pairs)
	return strings.Join(pairs, ",")
}

func escape(v string) string {
	r := strings.NewReplacer("\\", "\\\\", "\"", "\\\"", "\n", " ")
	return r.Replace(v)
}

func get(name, help, kind string, kv []string) *series {
	labels := renderLabels(kv)
	k := key(name, labels)
	s, ok := data[k]
	if !ok {
		s = &series{name: name, help: help, kind: kind, labels: labels}
		if kind == "histogram" {
			s.bucket = make([]uint64, len(durationBuckets))
		}
		data[k] = s
	}
	return s
}

// CounterInc увеличивает счётчик на единицу.
func CounterInc(name, help string, kv ...string) { CounterAdd(name, help, 1, kv...) }

// CounterAdd увеличивает счётчик на delta (отрицательные значения игнорируются).
func CounterAdd(name, help string, delta float64, kv ...string) {
	if delta < 0 {
		return
	}
	mu.Lock()
	defer mu.Unlock()
	get(name, help, "counter", kv).value += delta
}

// GaugeAdd изменяет текущее значение (может быть отрицательным).
func GaugeAdd(name, help string, delta float64, kv ...string) {
	mu.Lock()
	defer mu.Unlock()
	get(name, help, "gauge", kv).value += delta
}

// GaugeSet устанавливает абсолютное значение.
func GaugeSet(name, help string, value float64, kv ...string) {
	mu.Lock()
	defer mu.Unlock()
	get(name, help, "gauge", kv).value = value
}

// Observe записывает наблюдение в гистограмму длительности.
func Observe(name, help string, seconds float64, kv ...string) {
	mu.Lock()
	defer mu.Unlock()
	s := get(name, help, "histogram", kv)
	s.count++
	s.sum += seconds
	for i, b := range durationBuckets {
		if seconds <= b {
			s.bucket[i]++
		}
	}
}

// WriteText выводит все серии в Prometheus text format.
func WriteText(w io.Writer) error {
	mu.Lock()
	snapshot := make([]series, 0, len(data))
	for _, s := range data {
		copied := *s
		if s.bucket != nil {
			copied.bucket = append([]uint64(nil), s.bucket...)
		}
		snapshot = append(snapshot, copied)
	}
	mu.Unlock()

	sort.Slice(snapshot, func(i, j int) bool {
		if snapshot[i].name != snapshot[j].name {
			return snapshot[i].name < snapshot[j].name
		}
		return snapshot[i].labels < snapshot[j].labels
	})

	var buf strings.Builder
	var lastName string
	for _, s := range snapshot {
		if s.name != lastName {
			if s.help != "" {
				fmt.Fprintf(&buf, "# HELP %s %s\n", s.name, escape(s.help))
			}
			fmt.Fprintf(&buf, "# TYPE %s %s\n", s.name, s.kind)
			lastName = s.name
		}
		switch s.kind {
		case "histogram":
			for i, b := range durationBuckets {
				fmt.Fprintf(&buf, "%s_bucket%s %d\n", s.name, withLabel(s.labels, "le", strconv.FormatFloat(b, 'g', -1, 64)), s.bucket[i])
			}
			fmt.Fprintf(&buf, "%s_bucket%s %d\n", s.name, withLabel(s.labels, "le", "+Inf"), s.count)
			fmt.Fprintf(&buf, "%s_sum%s %s\n", s.name, wrap(s.labels), strconv.FormatFloat(s.sum, 'g', -1, 64))
			fmt.Fprintf(&buf, "%s_count%s %d\n", s.name, wrap(s.labels), s.count)
		default:
			fmt.Fprintf(&buf, "%s%s %s\n", s.name, wrap(s.labels), strconv.FormatFloat(s.value, 'g', -1, 64))
		}
	}
	_, err := io.WriteString(w, buf.String())
	return err
}

func wrap(labels string) string {
	if labels == "" {
		return ""
	}
	return "{" + labels + "}"
}

func withLabel(labels, name, value string) string {
	extra := name + "=\"" + escape(value) + "\""
	if labels == "" {
		return "{" + extra + "}"
	}
	return "{" + labels + "," + extra + "}"
}

// Reset очищает реестр. Нужен только тестам: в бою метрики живут весь процесс.
func Reset() {
	mu.Lock()
	defer mu.Unlock()
	data = map[string]*series{}
}
