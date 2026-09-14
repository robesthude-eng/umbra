package httpapi

import (
	"encoding/json"
	"testing"
)

// Клиенты до 0.19 присылают тело сообщения в поле ciphertext, новые — в payload.
// Оба варианта должны работать, причём payload имеет приоритет.
func TestMessageBodyAcceptsPayloadAndLegacyCiphertext(t *testing.T) {
	cases := []struct {
		name string
		body string
		want string
	}{
		{"новое поле payload", `{"payload":"cGF5"}`, "cGF5"},
		{"старое поле ciphertext", `{"ciphertext":"Y2lw"}`, "Y2lw"},
		{"payload приоритетнее", `{"payload":"cGF5","ciphertext":"Y2lw"}`, "cGF5"},
		{"пустое тело", `{}`, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var direct sendMessageRequest
			if err := json.Unmarshal([]byte(tc.body), &direct); err != nil {
				t.Fatalf("разбор sendMessageRequest: %v", err)
			}
			if got := direct.body(); got != tc.want {
				t.Fatalf("sendMessageRequest.body() = %q, ожидалось %q", got, tc.want)
			}

			var chat chatMessageRequest
			if err := json.Unmarshal([]byte(tc.body), &chat); err != nil {
				t.Fatalf("разбор chatMessageRequest: %v", err)
			}
			if got := chat.body(); got != tc.want {
				t.Fatalf("chatMessageRequest.body() = %q, ожидалось %q", got, tc.want)
			}
		})
	}
}

// Ответ сервера обязан содержать оба имени поля с одинаковым значением,
// иначе старые устройства перестанут показывать сообщения после обновления сервера.
func TestMessageResponseCarriesBothFieldNames(t *testing.T) {
	raw, err := json.Marshal(messageResponse{Payload: "cGF5", Ciphertext: "cGF5"})
	if err != nil {
		t.Fatalf("сериализация messageResponse: %v", err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("разбор ответа: %v", err)
	}
	for _, field := range []string{"payload", "ciphertext"} {
		if decoded[field] != "cGF5" {
			t.Fatalf("поле %q = %v, ожидалось cGF5", field, decoded[field])
		}
	}
}
