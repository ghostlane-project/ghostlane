package singbox

import (
	"context"
	"fmt"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
)

// Parse is `sing-box check` for an in-memory config: the typed decoder refuses
// unknown fields and wrong types.
func Parse(cfg []byte) (option.Options, error) {
	ctx := include.Context(context.Background())
	return sjson.UnmarshalExtendedContext[option.Options](ctx, cfg)
}

type Front struct {
	box    *box.Box
	cancel context.CancelFunc
}

// New builds the box without starting it: every option is validated, the tun
// device is not opened (that happens in Start).
func New(ctx context.Context, p FrontParams) (*Front, error) {
	cfg, err := BuildConfig(p)
	if err != nil {
		return nil, err
	}
	opts, err := Parse(cfg)
	if err != nil {
		return nil, fmt.Errorf("sing-box config: %w", err)
	}
	ctx, cancel := context.WithCancel(include.Context(ctx))
	b, err := box.New(box.Options{Context: ctx, Options: opts})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("sing-box: %w", err)
	}
	return &Front{box: b, cancel: cancel}, nil
}

func Start(ctx context.Context, p FrontParams) (*Front, error) {
	f, err := New(ctx, p)
	if err != nil {
		return nil, err
	}
	if err := f.box.Start(); err != nil {
		_ = f.Close()
		return nil, fmt.Errorf("sing-box start: %w", err)
	}
	return f, nil
}

func (f *Front) Close() error {
	defer f.cancel()
	return f.box.Close()
}
