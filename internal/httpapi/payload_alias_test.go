package httpapi

import (
	"encoding/json"
	"testing"
)

// С 0.19 тело сообщения передаётся только в payload: депрекейтед-алиас
// ciphertext удалён, и старое поле больше не должно приниматься.
func TestMessageBodyAcceptsOnlyPayload(t *testing.T) {
	cases := []struct {
		name string
		body string
		want string
	}{
		{"новое поле payload", `{"payload":"cGF5"}`, "cGF5"},
		{"старое поле ciphertext игнорируется", `{"ciphertext":"Y2lw"}`, ""},
		{"payload рядом с ciphertext", `{"payload":"cGF5","ciphertext":"Y2lw"}`, "cGF5"},
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

// Ответ сервера несёт только payload — дублирующее поле больше не отдаётся.
func TestMessageResponseCarriesOnlyPayload(t *testing.T) {
	raw, err := json.Marshal(messageResponse{Payload: "cGF5"})
	if err != nil {
		t.Fatalf("сериализация messageResponse: %v", err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("разбор ответа: %v", err)
	}
	if decoded["payload"] != "cGF5" {
		t.Fatalf("поле payload = %v, ожидалось cGF5", decoded["payload"])
	}
	if _, exists := decoded["ciphertext"]; exists {
		t.Fatal("ответ всё ещё содержит удалённый алиас ciphertext")
	}
}
