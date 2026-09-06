package ws

// Event — единый формат server push-события для клиентов.
type Event struct {
	Type string `json:"type"` // "message", "presence", ...
	Data any    `json:"data"`
}
