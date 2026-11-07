package main

import (
	"archive/tar"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync/atomic"

	"github.com/klauspost/compress/zstd"
)

const (
	payloadMagic = "ZEFIRPK1"
	footerSize   = 8 + 4 + len(payloadMagic)
)

type payloadMeta struct {
	Version string `json:"version"`
	Files   int    `json:"files"`
	Size    int64  `json:"size"`
}

type payload struct {
	path   string
	offset int64
	length int64
	meta   payloadMeta
}

func openPayload() (*payload, error) {
	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}
	f, err := os.Open(exe)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if info.Size() < int64(footerSize) {
		return nil, nil
	}
	foot := make([]byte, footerSize)
	if _, err := f.ReadAt(foot, info.Size()-int64(footerSize)); err != nil {
		return nil, err
	}
	if string(foot[12:]) != payloadMagic {
		return nil, nil
	}
	length := int64(binary.LittleEndian.Uint64(foot[:8]))
	metaLen := int64(binary.LittleEndian.Uint32(foot[8:12]))
	offset := info.Size() - int64(footerSize) - metaLen - length
	if offset <= 0 || length <= 0 {
		return nil, errors.New("повреждённый установщик")
	}
	raw := make([]byte, metaLen)
	if _, err := f.ReadAt(raw, offset+length); err != nil {
		return nil, err
	}
	p := &payload{path: exe, offset: offset, length: length}
	if err := json.Unmarshal(raw, &p.meta); err != nil {
		return nil, err
	}
	return p, nil
}

func (p *payload) writeStub(dst string) error {
	src, err := os.Open(p.path)
	if err != nil {
		return err
	}
	defer src.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	if _, err := io.CopyN(out, src, p.offset); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

func (p *payload) extract(ctx context.Context, dst string, done *atomic.Int64) error {
	src, err := os.Open(p.path)
	if err != nil {
		return err
	}
	defer src.Close()
	dec, err := zstd.NewReader(io.NewSectionReader(src, p.offset, p.length))
	if err != nil {
		return err
	}
	defer dec.Close()
	buf := make([]byte, 1<<20)
	tr := tar.NewReader(dec)
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return fmt.Errorf("архив: %w", err)
		}
		rel := filepath.FromSlash(h.Name)
		if !filepath.IsLocal(rel) {
			return fmt.Errorf("архив: плохой путь %q", h.Name)
		}
		target := filepath.Join(dst, rel)
		if h.Typeflag == tar.TypeDir {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		out, err := os.Create(target)
		if err != nil {
			return err
		}
		for {
			if err := ctx.Err(); err != nil {
				out.Close()
				return err
			}
			n, rerr := tr.Read(buf)
			if n > 0 {
				if _, werr := out.Write(buf[:n]); werr != nil {
					out.Close()
					return werr
				}
				done.Add(int64(n))
			}
			if errors.Is(rerr, io.EOF) {
				break
			}
			if rerr != nil {
				out.Close()
				return fmt.Errorf("архив: %w", rerr)
			}
		}
		if err := out.Close(); err != nil {
			return err
		}
	}
}
