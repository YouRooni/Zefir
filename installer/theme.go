package main

import (
	"image/color"
	"math"

	"golang.org/x/sys/windows/registry"
)

const fallbackAccent = 0x2F5D50

type palette struct {
	bg, container, high, primary, onPrimary, primaryContainer, onPrimaryContainer,
	text, muted, outline, danger, onDanger color.NRGBA
}

func rgb(v uint32) color.NRGBA {
	return color.NRGBA{R: uint8(v >> 16), G: uint8(v >> 8), B: uint8(v), A: 255}
}

func systemAccent() uint32 {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\DWM`, registry.QUERY_VALUE)
	if err != nil {
		return fallbackAccent
	}
	defer k.Close()
	v, _, err := k.GetIntegerValue("AccentColor")
	if err != nil {
		return fallbackAccent
	}
	return uint32(v&0xFF)<<16 | uint32(v&0xFF00) | uint32(v>>16)&0xFF
}

func buildPalette(seed uint32, dark bool) palette {
	_, c, h := toOklch(seed)
	neutral := func(l, chroma float64) color.NRGBA { return fromOklch(l, math.Min(c, chroma), h) }
	seedL, _, _ := toOklch(seed)
	if dark {
		return palette{
			bg: neutral(0.17, 0.008), container: neutral(0.235, 0.012), high: neutral(0.29, 0.014),
			primary: fromOklch(clamp(seedL, 0.76, 0.86), math.Min(c, 0.13), h), onPrimary: neutral(0.26, 0.05),
			primaryContainer: neutral(0.36, 0.07), onPrimaryContainer: neutral(0.91, 0.04),
			text: neutral(0.93, 0.006), muted: neutral(0.8, 0.012), outline: neutral(0.62, 0.016),
			danger: rgb(0xFFB4AB), onDanger: rgb(0x690005),
		}
	}
	return palette{
		bg: neutral(0.975, 0.006), container: neutral(0.93, 0.012), high: neutral(0.89, 0.016),
		primary: fromOklch(clamp(seedL, 0.36, 0.5), math.Min(c, 0.17), h), onPrimary: rgb(0xFFFFFF),
		primaryContainer: neutral(0.88, 0.06), onPrimaryContainer: neutral(0.32, 0.09),
		text: neutral(0.2, 0.008), muted: neutral(0.43, 0.014), outline: neutral(0.58, 0.018),
		danger: rgb(0xBA1A1A), onDanger: rgb(0xFFFFFF),
	}
}

func clamp(v, low, high float64) float64 { return math.Max(low, math.Min(v, high)) }

func toLinear(v float64) float64 {
	if v <= 0.04045 {
		return v / 12.92
	}
	return math.Pow((v+0.055)/1.055, 2.4)
}

func fromLinear(v float64) float64 {
	if v <= 0.0031308 {
		return v * 12.92
	}
	return 1.055*math.Pow(v, 1/2.4) - 0.055
}

func toOklch(v uint32) (l, c, h float64) {
	r := toLinear(float64(v>>16&0xFF) / 255)
	g := toLinear(float64(v>>8&0xFF) / 255)
	b := toLinear(float64(v&0xFF) / 255)
	lm := math.Cbrt(0.4122214708*r + 0.5363325363*g + 0.0514459929*b)
	mm := math.Cbrt(0.2119034982*r + 0.6806995451*g + 0.1073969566*b)
	sm := math.Cbrt(0.0883024619*r + 0.2817188376*g + 0.6299787005*b)
	l = 0.2104542553*lm + 0.7936177850*mm - 0.0040720468*sm
	a := 1.9779984951*lm - 2.4285922050*mm + 0.4505937099*sm
	bb := 0.0259040371*lm + 0.7827717662*mm - 0.8086757660*sm
	return l, math.Hypot(a, bb), math.Atan2(bb, a)
}

func oklchLinear(l, c, h float64) (r, g, b float64) {
	a, bb := c*math.Cos(h), c*math.Sin(h)
	lm := l + 0.3963377774*a + 0.2158037573*bb
	mm := l - 0.1055613458*a - 0.0638541728*bb
	sm := l - 0.0894841775*a - 1.2914855480*bb
	lm, mm, sm = lm*lm*lm, mm*mm*mm, sm*sm*sm
	return 4.0767416621*lm - 3.3077115913*mm + 0.2309699292*sm,
		-1.2684380046*lm + 2.6097574011*mm - 0.3413193965*sm,
		-0.0041960863*lm - 0.7034186147*mm + 1.7076147010*sm
}

func fromOklch(l, c, h float64) color.NRGBA {
	r, g, b := oklchLinear(l, c, h)
	for c > 0 && (min(r, g, b) < 0 || max(r, g, b) > 1) {
		c = math.Max(c-0.004, 0)
		r, g, b = oklchLinear(l, c, h)
	}
	channel := func(v float64) uint8 { return uint8(clamp(fromLinear(clamp(v, 0, 1)), 0, 1)*255 + 0.5) }
	return color.NRGBA{R: channel(r), G: channel(g), B: channel(b), A: 255}
}
