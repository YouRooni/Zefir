package main

import (
	"strconv"
	"strings"

	"gioui.org/f32"
	"gioui.org/op"
	"gioui.org/op/clip"
)

const markPetal = "M 107.166667 290.833333 C 98.333333 285 88.666667 274 85.833333 260.5 C 83 247 87 231 93.333333 217.333333 C 99.666667 203.666667 108.333333 192.333333 119.5 183.5 C 130.666667 174.666667 144.333333 168.333333 159 162.833333 C 173.666667 157.333333 189.333333 152.666667 204.833333 147.666667 C 220.333333 142.666667 235.666667 137.333333 249.166667 131.333333 C 262.666667 125.333333 274.333333 118.666667 283.333333 111.166667 C 292.333333 103.666667 298.666667 95.333333 305.833333 93.833333 C 313 92.333333 321 97.666667 328 105.333333 C 335 113 341 123 344.666667 133.333333 C 348.333333 143.666667 349.666667 154.333333 347.166667 164.833333 C 344.666667 175.333333 338.333333 185.666667 328.833333 195 C 319.333333 204.333333 306.666667 212.666667 292.833333 220 C 279 227.333333 264 233.666667 248.833333 239.833333 C 233.666667 246 218.333333 252 203.833333 258.166667 C 189.333333 264.333333 175.666667 270.666667 163.833333 277.333333 C 152 284 142 291 133 294.166667 C 124 297.333333 116 296.666667 107.166667 290.833333 Z"

var petalPoints = parsePetal()

func parsePetal() []f32.Point {
	var nums []float32
	for _, field := range strings.Fields(markPetal) {
		if v, err := strconv.ParseFloat(field, 32); err == nil {
			nums = append(nums, float32(v))
		}
	}
	points := make([]f32.Point, 0, len(nums)/2)
	for i := 0; i+1 < len(nums); i += 2 {
		points = append(points, f32.Pt(nums[i], nums[i+1]))
	}
	return points
}

func markPath(ops *op.Ops, size, angle float32) clip.PathSpec {
	scale := size / 512
	center := f32.Pt(256, 256)
	transform := f32.Affine2D{}.Rotate(center, angle).Scale(f32.Pt(0, 0), f32.Pt(scale, scale))
	var p clip.Path
	p.Begin(ops)
	for _, flip := range []bool{false, true} {
		at := func(i int) f32.Point {
			pt := petalPoints[i]
			if flip {
				pt = f32.Pt(512-pt.X, 512-pt.Y)
			}
			return transform.Transform(pt)
		}
		p.MoveTo(at(0))
		for i := 1; i+2 < len(petalPoints); i += 3 {
			p.CubeTo(at(i), at(i+1), at(i+2))
		}
		p.Close()
	}
	return p.End()
}
