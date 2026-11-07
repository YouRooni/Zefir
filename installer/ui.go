package main

import (
	"context"
	"errors"
	"fmt"
	"image"
	"image/color"
	"math"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"gioui.org/app"
	"gioui.org/f32"
	"gioui.org/font"
	"gioui.org/font/opentype"
	"gioui.org/io/key"
	"gioui.org/io/pointer"
	"gioui.org/io/system"
	"gioui.org/layout"
	"gioui.org/op"
	"gioui.org/op/clip"
	"gioui.org/op/paint"
	"gioui.org/text"
	"gioui.org/unit"
	"gioui.org/widget"
)

type (
	C = layout.Context
	D = layout.Dimensions
)

var startPage string

type setup struct {
	payload   *payload
	dir       string
	upgrading bool
	uninstall bool
	desktop   bool
	afterExit func()
}

type page int

const (
	pageWelcome page = iota
	pageRemove
	pageRunning
	pageProgress
	pageDone
	pageError
)

type spring struct{ x, v float32 }

type ui struct {
	setup
	win    *app.Window
	hwnd   uintptr
	shaper *text.Shaper
	pal    palette
	t      texts

	mu      sync.Mutex
	current page
	errText string
	busy    bool

	done       atomic.Int64
	cancel     context.CancelFunc
	cancelling atomic.Bool
	finished   chan struct{}

	dir       string
	removeAll widget.Bool
	desktopB  widget.Bool

	closeB, minB, folderB, primaryB, secondaryB widget.Clickable

	springs   map[string]*spring
	lastFrame time.Time
	dt        float32
	animating bool
	shown     page
	pageAnim  float32
}

func runUI(s setup) {
	u := &ui{setup: s, dir: s.dir, springs: map[string]*spring{}, pageAnim: 1, t: translations[systemLang()]}
	u.pal = buildPalette(systemAccent(), systemDark())
	u.desktopB.Value = s.desktop
	if s.uninstall {
		u.current = pageRemove
	}
	u.applyStartPage()
	u.shown = u.current
	u.shaper = text.NewShaper(text.NoSystemFonts(), text.WithCollection(loadFonts()))
	u.win = new(app.Window)
	u.win.Option(
		app.Title("Zefir"),
		app.Decorated(false),
		app.Size(480, 540),
		app.MinSize(480, 540),
		app.MaxSize(480, 540),
	)
	u.win.Perform(system.ActionCenter)
	go u.loop()
	app.Main()
}

func (u *ui) applyStartPage() {
	switch startPage {
	case "progress":
		u.current = pageProgress
		u.done.Store(u.totalSize() * 42 / 100)
	case "done":
		u.current = pageDone
	case "error":
		u.current, u.errText = pageError, u.t.errNotOurs
	case "running":
		u.current = pageRunning
	}
}

func (u *ui) totalSize() int64 {
	if u.payload != nil {
		return u.payload.meta.Size
	}
	return 1
}

func loadFonts() []font.FontFace {
	var faces []font.FontFace
	fonts := `C:\Windows\Fonts\`
	if dir := os.Getenv("WINDIR"); dir != "" {
		fonts = filepath.Join(dir, "Fonts") + `\`
	}
	for _, name := range []string{"segoeui.ttf", "seguisb.ttf"} {
		raw, err := os.ReadFile(fonts + name)
		if err != nil {
			continue
		}
		if face, err := opentype.Parse(raw); err == nil {
			faces = append(faces, font.FontFace{Font: face.Font(), Face: face})
		}
	}
	if systemLang() == "zh" {
		if raw, err := os.ReadFile(fonts + "msyh.ttc"); err == nil {
			if col, err := opentype.ParseCollection(raw); err == nil {
				faces = append(faces, col...)
			}
		}
	}
	return faces
}

func (u *ui) loop() {
	var ops op.Ops
	for {
		switch e := u.win.Event().(type) {
		case app.DestroyEvent:
			u.quit()
		case app.Win32ViewEvent:
			u.hwnd = e.HWND
		case app.FrameEvent:
			gtx := app.NewContext(&ops, e)
			u.frame(gtx)
			e.Frame(gtx.Ops)
		}
	}
}

func (u *ui) quit() {
	u.mu.Lock()
	cancel, finished := u.cancel, u.finished
	u.mu.Unlock()
	if cancel != nil {
		cancel()
		select {
		case <-finished:
		case <-time.After(5 * time.Second):
		}
	}
	u.afterExit()
	os.Exit(0)
}

func (u *ui) page() page {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.current
}

func (u *ui) goTo(p page) {
	u.mu.Lock()
	u.current = p
	u.mu.Unlock()
	u.win.Invalidate()
}

func (u *ui) showError(err error) {
	msg := err.Error()
	switch {
	case errors.Is(err, errAppBusy):
		msg = u.t.errBusy
	case errors.Is(err, errDirNotOurs):
		msg = u.t.errNotOurs
	case errors.Is(err, errNoAccess):
		msg = u.t.errNoAccess
	}
	u.mu.Lock()
	u.errText = msg
	u.mu.Unlock()
	u.goTo(pageError)
}

func (u *ui) start(closeFirst bool) {
	if !closeFirst && len(processesIn(u.dir)) > 0 {
		u.goTo(pageRunning)
		return
	}
	if !u.uninstall {
		if err := dirUsable(u.dir); err != nil {
			u.showError(err)
			return
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	finished := make(chan struct{})
	u.mu.Lock()
	u.cancel, u.finished = cancel, finished
	u.mu.Unlock()
	u.cancelling.Store(false)
	u.done.Store(0)
	u.goTo(pageProgress)
	go func() {
		defer close(finished)
		defer cancel()
		var err error
		if u.uninstall {
			err = uninstall(u.dir, u.removeAll.Value)
		} else {
			if closeFirst {
				err = closeProcesses(u.dir)
			}
			if err == nil {
				err = install(ctx, u.payload, u.dir, u.desktopB.Value, &u.done)
			}
		}
		u.mu.Lock()
		u.cancel = nil
		u.mu.Unlock()
		switch {
		case errors.Is(err, context.Canceled):
			u.goTo(pageWelcome)
		case err != nil:
			u.showError(err)
		default:
			u.goTo(pageDone)
		}
	}()
}

func (u *ui) pickFolder() {
	if u.busy {
		return
	}
	u.busy = true
	go func() {
		defer func() { u.busy = false }()
		start := filepath.Dir(u.dir)
		for !fileExists(start) && filepath.Dir(start) != start {
			start = filepath.Dir(start)
		}
		picked, err := chooseFolder(u.hwnd, u.t.pickFolder, start)
		if errors.Is(err, errDialogCancelled) {
			return
		}
		if err != nil {
			u.showError(err)
			return
		}
		if picked != "" {
			u.dir = normalizeDir(picked)
			u.win.Invalidate()
		}
	}()
}

func (u *ui) anim(key string, target, stiffness, damping float32) float32 {
	s, ok := u.springs[key]
	if !ok {
		s = &spring{x: target}
		u.springs[key] = s
	}
	dt := u.dt
	for dt > 0 {
		step := min(dt, 1.0/240)
		accel := stiffness*(target-s.x) - 2*damping*float32(math.Sqrt(float64(stiffness)))*s.v
		s.v += accel * step
		s.x += s.v * step
		dt -= step
	}
	if abs32(target-s.x) < 0.001 && abs32(s.v) < 0.001 {
		s.x, s.v = target, 0
	} else {
		u.animating = true
	}
	return s.x
}

func abs32(v float32) float32 {
	if v < 0 {
		return -v
	}
	return v
}

func (u *ui) frame(gtx C) {
	if !u.lastFrame.IsZero() {
		u.dt = min(float32(gtx.Now.Sub(u.lastFrame).Seconds()), 0.05)
	}
	u.lastFrame = gtx.Now
	u.animating = false
	u.handle(gtx)

	paint.Fill(gtx.Ops, u.pal.bg)

	cur := u.page()
	if cur != u.shown {
		u.shown, u.pageAnim = cur, 0
	}
	if u.pageAnim < 1 {
		u.pageAnim = min(u.pageAnim+u.dt/0.26, 1)
		u.animating = true
	}
	eased := 1 - (1-u.pageAnim)*(1-u.pageAnim)*(1-u.pageAnim)

	layout.Flex{Axis: layout.Vertical}.Layout(gtx,
		layout.Rigid(u.titleBar),
		layout.Flexed(1, func(gtx C) D {
			defer paint.PushOpacity(gtx.Ops, eased).Pop()
			defer op.Offset(image.Pt(0, int((1-eased)*float32(gtx.Dp(14))))).Push(gtx.Ops).Pop()
			return layout.UniformInset(0).Layout(gtx, func(gtx C) D {
				return layout.Inset{Left: 28, Right: 28, Bottom: 28}.Layout(gtx, u.body(cur))
			})
		}),
	)
	if u.animating || cur == pageProgress {
		gtx.Execute(op.InvalidateCmd{})
	}
}

func (u *ui) handle(gtx C) {
	if u.closeB.Clicked(gtx) {
		go u.quit()
	}
	if u.minB.Clicked(gtx) {
		u.win.Perform(system.ActionMinimize)
	}
	if u.folderB.Clicked(gtx) {
		u.pickFolder()
	}
	u.desktopB.Update(gtx)
	u.removeAll.Update(gtx)
	primary := u.primaryB.Clicked(gtx)
	secondary := u.secondaryB.Clicked(gtx)
	for {
		ev, ok := gtx.Event(key.Filter{Name: key.NameReturn})
		if !ok {
			break
		}
		if k, ok := ev.(key.Event); ok && k.State == key.Press {
			primary = true
		}
	}
	switch u.page() {
	case pageWelcome, pageRemove:
		if primary {
			u.start(false)
		}
		if secondary {
			go u.quit()
		}
	case pageRunning:
		if primary {
			u.start(true)
		}
		if secondary {
			u.goTo(u.firstPage())
		}
	case pageProgress:
		if secondary && !u.uninstall && !u.cancelling.Load() {
			u.mu.Lock()
			if u.cancel != nil {
				u.cancel()
				u.cancelling.Store(true)
			}
			u.mu.Unlock()
		}
	case pageDone:
		if primary {
			if !u.uninstall {
				launchApp(u.dir)
			}
			go u.quit()
		}
		if secondary {
			go u.quit()
		}
	case pageError:
		if primary {
			u.goTo(u.firstPage())
		}
		if secondary {
			go u.quit()
		}
	}
}

func (u *ui) firstPage() page {
	if u.uninstall {
		return pageRemove
	}
	return pageWelcome
}

func (u *ui) body(p page) layout.Widget {
	switch p {
	case pageRemove:
		return u.removePage
	case pageRunning:
		return u.runningPage
	case pageProgress:
		return u.progressPage
	case pageDone:
		return u.donePage
	case pageError:
		return u.errorPage
	}
	return u.welcomePage
}

func (u *ui) version() string {
	if u.payload == nil {
		return ""
	}
	return u.payload.meta.Version
}

func (u *ui) welcomePage(gtx C) D {
	action := u.t.install
	if u.upgrading {
		action = u.t.update
	}
	return layout.Flex{Axis: layout.Vertical, Alignment: layout.Middle}.Layout(gtx,
		layout.Flexed(1, func(gtx C) D {
			return layout.Center.Layout(gtx, func(gtx C) D {
				return layout.Flex{Axis: layout.Vertical, Alignment: layout.Middle}.Layout(gtx,
					layout.Rigid(func(gtx C) D { return u.logo(gtx, 92, 0) }),
					layout.Rigid(layout.Spacer{Height: 16}.Layout),
					layout.Rigid(func(gtx C) D { return u.label(gtx, "Zefir", 34, font.SemiBold, u.pal.text, text.Middle) }),
					layout.Rigid(layout.Spacer{Height: 2}.Layout),
					layout.Rigid(func(gtx C) D {
						return u.label(gtx, fmt.Sprintf(u.t.version, u.version()), 14, font.Normal, u.pal.muted, text.Middle)
					}),
				)
			})
		}),
		layout.Rigid(func(gtx C) D { return u.folderRow(gtx) }),
		layout.Rigid(layout.Spacer{Height: 4}.Layout),
		layout.Rigid(func(gtx C) D { return u.switchRow(gtx, "desktop", &u.desktopB, u.t.desktop, 6, 24) }),
		layout.Rigid(layout.Spacer{Height: 10}.Layout),
		layout.Rigid(func(gtx C) D { return u.label(gtx, u.t.noAdmin, 13, font.Normal, u.pal.muted, text.Middle) }),
		layout.Rigid(layout.Spacer{Height: 18}.Layout),
		layout.Rigid(func(gtx C) D { return u.actions(gtx, u.t.cancel, action, false) }),
	)
}

func (u *ui) removePage(gtx C) D {
	return layout.Flex{Axis: layout.Vertical, Alignment: layout.Middle}.Layout(gtx,
		layout.Flexed(1, func(gtx C) D {
			return layout.Center.Layout(gtx, func(gtx C) D {
				return u.hero(gtx, func(gtx C) D { return u.logo(gtx, 92, 0) }, u.t.removeTitle, u.t.removeHint)
			})
		}),
		layout.Rigid(func(gtx C) D { return u.switchRow(gtx, "remove", &u.removeAll, u.t.removeData, 24, 24) }),
		layout.Rigid(layout.Spacer{Height: 18}.Layout),
		layout.Rigid(func(gtx C) D { return u.actions(gtx, u.t.cancel, u.t.remove, true) }),
	)
}

func (u *ui) runningPage(gtx C) D {
	return layout.Flex{Axis: layout.Vertical, Alignment: layout.Middle}.Layout(gtx,
		layout.Flexed(1, func(gtx C) D {
			return layout.Center.Layout(gtx, func(gtx C) D {
				return u.hero(gtx, func(gtx C) D { return u.logo(gtx, 92, 0) }, u.t.runningTitle, u.t.runningHint)
			})
		}),
		layout.Rigid(func(gtx C) D { return u.actions(gtx, u.t.cancel, u.t.closeApp, false) }),
	)
}

func (u *ui) progressPage(gtx C) D {
	title := u.t.installing
	switch {
	case u.cancelling.Load():
		title = u.t.cancelling
	case u.uninstall:
		title = u.t.removing
	case u.upgrading:
		title = u.t.updating
	}
	progress := float32(1)
	if !u.uninstall {
		progress = min(float32(u.done.Load())/float32(u.totalSize()), 1)
	}
	shown := u.anim("progress", progress, 60, 1)
	angle := float32(math.Mod(float64(gtx.Now.UnixMilli())/1000*1.5, 2*math.Pi))
	return layout.Flex{Axis: layout.Vertical, Alignment: layout.Middle}.Layout(gtx,
		layout.Flexed(1, func(gtx C) D {
			return layout.Center.Layout(gtx, func(gtx C) D {
				return layout.Flex{Axis: layout.Vertical, Alignment: layout.Middle}.Layout(gtx,
					layout.Rigid(func(gtx C) D { return u.logo(gtx, 112, angle) }),
					layout.Rigid(layout.Spacer{Height: 24}.Layout),
					layout.Rigid(func(gtx C) D { return u.label(gtx, title, 26, font.SemiBold, u.pal.text, text.Middle) }),
				)
			})
		}),
		layout.Rigid(func(gtx C) D { return u.wavy(gtx, shown) }),
		layout.Rigid(layout.Spacer{Height: 10}.Layout),
		layout.Rigid(func(gtx C) D {
			if u.uninstall {
				return D{}
			}
			return u.label(gtx, fmt.Sprintf("%d%%", int(shown*100+0.5)), 14, font.Normal, u.pal.muted, text.Middle)
		}),
		layout.Rigid(layout.Spacer{Height: 26}.Layout),
		layout.Rigid(func(gtx C) D {
			if u.uninstall {
				return layout.Spacer{Height: 56}.Layout(gtx)
			}
			return u.button(gtx, "secondary", &u.secondaryB, u.t.cancel, styleOutlined, 28, 28)
		}),
	)
}

func (u *ui) donePage(gtx C) D {
	title := u.t.installed
	switch {
	case u.uninstall:
		title = u.t.removed
	case u.upgrading:
		title = u.t.updated
	}
	icon := func(gtx C) D { return u.badge(gtx, u.pal.primary, u.pal.onPrimary, iconCheck) }
	return layout.Flex{Axis: layout.Vertical, Alignment: layout.Middle}.Layout(gtx,
		layout.Flexed(1, func(gtx C) D {
			return layout.Center.Layout(gtx, func(gtx C) D { return u.hero(gtx, icon, title, "") })
		}),
		layout.Rigid(func(gtx C) D {
			if u.uninstall {
				return u.button(gtx, "primary", &u.primaryB, u.t.closeBtn, styleFilled, 28, 28)
			}
			return u.actions(gtx, u.t.closeBtn, u.t.launch, false)
		}),
	)
}

func (u *ui) errorPage(gtx C) D {
	u.mu.Lock()
	msg := u.errText
	u.mu.Unlock()
	icon := func(gtx C) D { return u.badge(gtx, u.pal.danger, u.pal.onDanger, iconCross) }
	return layout.Flex{Axis: layout.Vertical, Alignment: layout.Middle}.Layout(gtx,
		layout.Flexed(1, func(gtx C) D {
			return layout.Center.Layout(gtx, func(gtx C) D { return u.hero(gtx, icon, u.t.errTitle, msg) })
		}),
		layout.Rigid(func(gtx C) D { return u.actions(gtx, u.t.closeBtn, u.t.retry, false) }),
	)
}

func (u *ui) hero(gtx C, icon layout.Widget, title, hint string) D {
	return layout.Flex{Axis: layout.Vertical, Alignment: layout.Middle}.Layout(gtx,
		layout.Rigid(icon),
		layout.Rigid(layout.Spacer{Height: 20}.Layout),
		layout.Rigid(func(gtx C) D { return u.label(gtx, title, 26, font.SemiBold, u.pal.text, text.Middle) }),
		layout.Rigid(func(gtx C) D {
			if hint == "" {
				return D{}
			}
			return layout.Inset{Top: 8}.Layout(gtx, func(gtx C) D {
				return u.label(gtx, hint, 15, font.Normal, u.pal.muted, text.Middle)
			})
		}),
	)
}

func (u *ui) label(gtx C, txt string, size unit.Sp, weight font.Weight, col color.NRGBA, align text.Alignment) D {
	gtx.Constraints.Min.X = 0
	macro := op.Record(gtx.Ops)
	paint.ColorOp{Color: col}.Add(gtx.Ops)
	material := macro.Stop()
	return widget.Label{Alignment: align}.Layout(gtx, u.shaper, font.Font{Weight: weight}, size, txt, material)
}

func (u *ui) line(gtx C, txt string, size unit.Sp, weight font.Weight, col color.NRGBA) D {
	gtx.Constraints.Min.X = 0
	macro := op.Record(gtx.Ops)
	paint.ColorOp{Color: col}.Add(gtx.Ops)
	material := macro.Stop()
	return widget.Label{MaxLines: 1, Truncator: "…"}.Layout(gtx, u.shaper, font.Font{Weight: weight}, size, txt, material)
}

func (u *ui) logo(gtx C, sizeDp unit.Dp, angle float32) D {
	size := gtx.Dp(sizeDp)
	rect := image.Rect(0, 0, size, size)
	paint.FillShape(gtx.Ops, u.pal.primaryContainer, clip.UniformRRect(rect, size*116/512).Op(gtx.Ops))
	spec := markPath(gtx.Ops, float32(size), angle)
	paint.FillShape(gtx.Ops, u.pal.onPrimaryContainer, clip.Outline{Path: spec}.Op())
	return D{Size: rect.Max}
}

type glyph int

const (
	iconCheck glyph = iota
	iconCross
	iconMinus
)

func strokeIcon(gtx C, g glyph, c f32.Point, r, width float32, col color.NRGBA) {
	var p clip.Path
	p.Begin(gtx.Ops)
	switch g {
	case iconCheck:
		p.MoveTo(f32.Pt(c.X-r, c.Y+r*0.05))
		p.LineTo(f32.Pt(c.X-r*0.25, c.Y+r*0.75))
		p.LineTo(f32.Pt(c.X+r, c.Y-r*0.65))
	case iconCross:
		p.MoveTo(f32.Pt(c.X-r, c.Y-r))
		p.LineTo(f32.Pt(c.X+r, c.Y+r))
		p.MoveTo(f32.Pt(c.X+r, c.Y-r))
		p.LineTo(f32.Pt(c.X-r, c.Y+r))
	case iconMinus:
		p.MoveTo(f32.Pt(c.X-r, c.Y))
		p.LineTo(f32.Pt(c.X+r, c.Y))
	}
	paint.FillShape(gtx.Ops, col, clip.Stroke{Path: p.End(), Width: width}.Op())
}

func (u *ui) badge(gtx C, bg, fg color.NRGBA, g glyph) D {
	scale := u.anim(fmt.Sprintf("badge%d", g), 1, 260, 0.55)
	if _, seen := u.springs[fmt.Sprintf("seen%d", g)]; !seen {
		u.springs[fmt.Sprintf("seen%d", g)] = &spring{}
		u.springs[fmt.Sprintf("badge%d", g)].x = 0.4
	}
	size := gtx.Dp(92)
	c := f32.Pt(float32(size)/2, float32(size)/2)
	defer op.Affine(f32.Affine2D{}.Scale(c, f32.Pt(scale, scale))).Push(gtx.Ops).Pop()
	paint.FillShape(gtx.Ops, bg, clip.Ellipse{Max: image.Pt(size, size)}.Op(gtx.Ops))
	strokeIcon(gtx, g, c, float32(gtx.Dp(18)), float32(gtx.Dp(5)), fg)
	return D{Size: image.Pt(size, size)}
}

func (u *ui) titleBar(gtx C) D {
	height := gtx.Dp(44)
	size := image.Pt(gtx.Constraints.Max.X, height)
	area := clip.Rect{Max: size}.Push(gtx.Ops)
	system.ActionInputOp(system.ActionMove).Add(gtx.Ops)
	area.Pop()
	for i, item := range []struct {
		click *widget.Clickable
		g     glyph
	}{{&u.closeB, iconCross}, {&u.minB, iconMinus}} {
		x := size.X - gtx.Dp(40)*(i+1) - gtx.Dp(6)
		stack := op.Offset(image.Pt(x, gtx.Dp(6))).Push(gtx.Ops)
		u.windowButton(gtx, item.click, item.g)
		stack.Pop()
	}
	return D{Size: size}
}

func (u *ui) windowButton(gtx C, click *widget.Clickable, g glyph) D {
	size := gtx.Dp(36)
	gtx.Constraints = layout.Exact(image.Pt(size, size))
	return click.Layout(gtx, func(gtx C) D {
		if click.Hovered() {
			paint.FillShape(gtx.Ops, u.pal.container, clip.Ellipse{Max: image.Pt(size, size)}.Op(gtx.Ops))
		}
		c := f32.Pt(float32(size)/2, float32(size)/2)
		strokeIcon(gtx, g, c, float32(gtx.Dp(5)), float32(gtx.Dp(1.5)), u.pal.muted)
		return D{Size: image.Pt(size, size)}
	})
}

func (u *ui) folderRow(gtx C) D {
	return u.rowBox(gtx, &u.folderB, 24, 6, func(gtx C) D {
		return layout.Flex{Alignment: layout.Middle}.Layout(gtx,
			layout.Flexed(1, fillWidth(func(gtx C) D {
				return layout.Flex{Axis: layout.Vertical}.Layout(gtx,
					layout.Rigid(func(gtx C) D { return u.line(gtx, u.t.folder, 15, font.SemiBold, u.pal.text) }),
					layout.Rigid(func(gtx C) D { return u.line(gtx, u.dir, 13, font.Normal, u.pal.muted) }),
				)
			})),
			layout.Rigid(layout.Spacer{Width: 12}.Layout),
			layout.Rigid(u.folderIcon),
		)
	})
}

func fillWidth(w layout.Widget) layout.Widget {
	return func(gtx C) D {
		gtx.Constraints.Min = image.Point{}
		d := w(gtx)
		d.Size.X = gtx.Constraints.Max.X
		return d
	}
}

func (u *ui) folderIcon(gtx C) D {
	size := gtx.Dp(22)
	w, h := float32(size), float32(size)*0.8
	top := (float32(size) - h) / 2
	var p clip.Path
	p.Begin(gtx.Ops)
	p.MoveTo(f32.Pt(0, top+h*0.12))
	p.LineTo(f32.Pt(w*0.38, top+h*0.12))
	p.LineTo(f32.Pt(w*0.5, top+h*0.28))
	p.LineTo(f32.Pt(w, top+h*0.28))
	p.LineTo(f32.Pt(w, top+h))
	p.LineTo(f32.Pt(0, top+h))
	p.Close()
	paint.FillShape(gtx.Ops, u.pal.primary, clip.Outline{Path: p.End()}.Op())
	return D{Size: image.Pt(size, size)}
}

func (u *ui) switchRow(gtx C, key string, b *widget.Bool, label string, top, bottom unit.Dp) D {
	return b.Layout(gtx, func(gtx C) D {
		return u.rowShape(gtx, b.Hovered(), top, bottom, func(gtx C) D {
			return layout.Flex{Alignment: layout.Middle}.Layout(gtx,
				layout.Flexed(1, fillWidth(func(gtx C) D { return u.line(gtx, label, 15, font.SemiBold, u.pal.text) })),
				layout.Rigid(layout.Spacer{Width: 12}.Layout),
				layout.Rigid(func(gtx C) D { return u.toggle(gtx, key, b.Value) }),
			)
		})
	})
}

func (u *ui) rowBox(gtx C, click *widget.Clickable, top, bottom unit.Dp, w layout.Widget) D {
	return click.Layout(gtx, func(gtx C) D {
		return u.rowShape(gtx, click.Hovered(), top, bottom, w)
	})
}

func (u *ui) rowShape(gtx C, hovered bool, top, bottom unit.Dp, w layout.Widget) D {
	gtx.Constraints.Min.X = gtx.Constraints.Max.X
	height := gtx.Dp(66)
	gtx.Constraints.Min.Y, gtx.Constraints.Max.Y = height, height
	rect := image.Rect(0, 0, gtx.Constraints.Max.X, height)
	t, b := gtx.Dp(top), gtx.Dp(bottom)
	col := u.pal.container
	if hovered {
		col = u.pal.high
	}
	paint.FillShape(gtx.Ops, col, clip.RRect{Rect: rect, NW: t, NE: t, SE: b, SW: b}.Op(gtx.Ops))
	area := clip.Rect(rect).Push(gtx.Ops)
	pointer.CursorPointer.Add(gtx.Ops)
	area.Pop()
	layout.Inset{Left: 20, Right: 20}.Layout(gtx, w)
	return D{Size: rect.Max}
}

func (u *ui) toggle(gtx C, key string, on bool) D {
	target := float32(0)
	if on {
		target = 1
	}
	x := u.anim("toggle"+key, target, 520, 0.72)
	w, h := gtx.Dp(52), gtx.Dp(32)
	rect := image.Rect(0, 0, w, h)
	track, thumb := u.pal.high, u.pal.outline
	if on {
		track, thumb = u.pal.primary, u.pal.onPrimary
	}
	shape := clip.UniformRRect(rect, h/2)
	if on {
		paint.FillShape(gtx.Ops, track, shape.Op(gtx.Ops))
	} else {
		paint.FillShape(gtx.Ops, u.pal.outline, shape.Op(gtx.Ops))
		paint.FillShape(gtx.Ops, track, insetShape(shape, gtx.Dp(2)).Op(gtx.Ops))
	}
	d := float32(gtx.Dp(16)) + float32(gtx.Dp(8))*max(min(x, 1), 0)
	travel := float32(w - h)
	cx := float32(h)/2 + travel*x
	cy := float32(h) / 2
	circle := image.Rect(int(cx-d/2), int(cy-d/2), int(cx+d/2), int(cy+d/2))
	paint.FillShape(gtx.Ops, thumb, clip.Ellipse{Min: circle.Min, Max: circle.Max}.Op(gtx.Ops))
	return D{Size: rect.Max}
}

type buttonStyle int

const (
	styleFilled buttonStyle = iota
	styleOutlined
	styleDanger
)

func (u *ui) actions(gtx C, secondary, primary string, danger bool) D {
	style := styleFilled
	if danger {
		style = styleDanger
	}
	return layout.Flex{Spacing: layout.SpaceBetween}.Layout(gtx,
		layout.Flexed(1, func(gtx C) D {
			return u.button(gtx, "secondary", &u.secondaryB, secondary, styleOutlined, 28, 6)
		}),
		layout.Rigid(layout.Spacer{Width: 4}.Layout),
		layout.Flexed(1, func(gtx C) D {
			return u.button(gtx, "primary", &u.primaryB, primary, style, 6, 28)
		}),
	)
}

func (u *ui) button(gtx C, id string, click *widget.Clickable, label string, style buttonStyle, left, right unit.Dp) D {
	width, height := gtx.Constraints.Max.X, gtx.Dp(56)
	gtx.Constraints = layout.Exact(image.Pt(width, height))
	press := float32(0)
	if click.Pressed() {
		press = 1
	}
	hover := float32(0)
	if click.Hovered() {
		hover = 1
	}
	pressed := u.anim("press"+id, press, 900, 0.7)
	hovered := u.anim("hover"+id, hover, 300, 1)
	return click.Layout(gtx, func(gtx C) D {
		size := image.Pt(width, height)
		c := f32.Pt(float32(width)/2, float32(height)/2)
		scale := 1 - 0.03*max(pressed, 0)
		defer op.Affine(f32.Affine2D{}.Scale(c, f32.Pt(scale, scale))).Push(gtx.Ops).Pop()

		morph := func(r unit.Dp) int {
			px := float32(gtx.Dp(r))
			full := float32(height) / 2
			return int(px + (full-px)*0.55*max(min(pressed, 1), 0))
		}
		l, r := morph(left), morph(right)
		shape := clip.RRect{Rect: image.Rectangle{Max: size}, NW: l, SW: l, NE: r, SE: r}

		var bg, fg color.NRGBA
		switch style {
		case styleFilled:
			bg, fg = u.pal.primary, u.pal.onPrimary
		case styleDanger:
			bg, fg = u.pal.danger, u.pal.onDanger
		default:
			bg, fg = u.pal.bg, u.pal.text
		}
		face := shape
		if style == styleOutlined {
			paint.FillShape(gtx.Ops, u.pal.outline, shape.Op(gtx.Ops))
			face = insetShape(shape, max(gtx.Dp(1.5), 1))
		}
		paint.FillShape(gtx.Ops, bg, face.Op(gtx.Ops))
		overlay := fg
		overlay.A = uint8(max(0.08*hovered, 0.14*pressed) * 255)
		paint.FillShape(gtx.Ops, overlay, face.Op(gtx.Ops))
		area := clip.Rect{Max: size}.Push(gtx.Ops)
		pointer.CursorPointer.Add(gtx.Ops)
		area.Pop()
		layout.Center.Layout(gtx, func(gtx C) D { return u.line(gtx, label, 16, font.SemiBold, fg) })
		return D{Size: size}
	})
}

func insetShape(s clip.RRect, by int) clip.RRect {
	return clip.RRect{
		Rect: s.Rect.Inset(by),
		NW:   max(s.NW-by, 0),
		NE:   max(s.NE-by, 0),
		SE:   max(s.SE-by, 0),
		SW:   max(s.SW-by, 0),
	}
}

func (u *ui) wavy(gtx C, progress float32) D {
	width, height := gtx.Constraints.Max.X, gtx.Dp(20)
	w, h := float32(width), float32(height)
	stroke := float32(gtx.Dp(4))
	inset := stroke / 2
	gap := float32(gtx.Dp(6))
	amp := float32(gtx.Dp(3)) * min(progress*12, 1) * min((1-progress)*20, 1)
	wavelength := float32(gtx.Dp(36))
	phase := float32(math.Mod(float64(gtx.Now.UnixMilli())/1000*2.2, 2*math.Pi))
	mid := h / 2
	end := inset + (w-2*inset)*progress

	if end+gap < w-inset {
		var p clip.Path
		p.Begin(gtx.Ops)
		p.MoveTo(f32.Pt(end+gap, mid))
		p.LineTo(f32.Pt(w-inset, mid))
		paint.FillShape(gtx.Ops, u.pal.high, clip.Stroke{Path: p.End(), Width: stroke}.Op())
		dot := int(stroke)
		x, y := int(w-inset)-dot/2, int(mid)-dot/2
		paint.FillShape(gtx.Ops, u.pal.primary, clip.Ellipse{Min: image.Pt(x, y), Max: image.Pt(x+dot, y+dot)}.Op(gtx.Ops))
	}
	if progress > 0.002 {
		wave := func(x float32) f32.Point {
			return f32.Pt(x, mid+amp*float32(math.Sin(float64(x/wavelength*2*math.Pi-phase))))
		}
		var p clip.Path
		p.Begin(gtx.Ops)
		p.MoveTo(wave(inset))
		step := float32(gtx.Dp(2))
		for x := inset + step; x < end; x += step {
			p.LineTo(wave(x))
		}
		p.LineTo(wave(end))
		paint.FillShape(gtx.Ops, u.pal.primary, clip.Stroke{Path: p.End(), Width: stroke}.Op())
		for _, x := range []float32{inset, end} {
			pt := wave(x)
			r := stroke / 2
			paint.FillShape(gtx.Ops, u.pal.primary, clip.Ellipse{
				Min: image.Pt(int(pt.X-r), int(pt.Y-r)),
				Max: image.Pt(int(pt.X+r+0.5), int(pt.Y+r+0.5)),
			}.Op(gtx.Ops))
		}
	}
	return D{Size: image.Pt(width, height)}
}
