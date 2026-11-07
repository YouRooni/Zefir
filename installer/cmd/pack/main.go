package main

import (
	"archive/tar"
	"encoding/binary"
	"encoding/json"
	"flag"
	"io"
	"log"
	"os"
	"path/filepath"

	"github.com/klauspost/compress/zstd"
)

const magic = "ZEFIRPK1"

func main() {
	stub := flag.String("stub", "", "exe установщика")
	dir := flag.String("dir", "", "папка приложения")
	out := flag.String("out", "", "итоговый setup")
	version := flag.String("version", "", "версия")
	flag.Parse()
	if *stub == "" || *dir == "" || *out == "" || *version == "" {
		flag.Usage()
		os.Exit(2)
	}
	if err := pack(*stub, *dir, *out, *version); err != nil {
		os.Remove(*out)
		log.Fatal(err)
	}
}

func pack(stub, dir, out, version string) error {
	dst, err := os.Create(out)
	if err != nil {
		return err
	}
	defer dst.Close()
	src, err := os.Open(stub)
	if err != nil {
		return err
	}
	defer src.Close()
	if _, err := io.Copy(dst, src); err != nil {
		return err
	}
	start, err := dst.Seek(0, io.SeekCurrent)
	if err != nil {
		return err
	}

	enc, err := zstd.NewWriter(dst, zstd.WithEncoderLevel(zstd.SpeedBestCompression))
	if err != nil {
		return err
	}
	tw := tar.NewWriter(enc)
	files, size := 0, int64(0)
	err = filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
		if err != nil || path == dir {
			return err
		}
		rel, err := filepath.Rel(dir, path)
		if err != nil {
			return err
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		h, err := tar.FileInfoHeader(info, "")
		if err != nil {
			return err
		}
		h.Name = filepath.ToSlash(rel)
		if err := tw.WriteHeader(h); err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		f, err := os.Open(path)
		if err != nil {
			return err
		}
		defer f.Close()
		n, err := io.Copy(tw, f)
		files++
		size += n
		return err
	})
	if err != nil {
		return err
	}
	if err := tw.Close(); err != nil {
		return err
	}
	if err := enc.Close(); err != nil {
		return err
	}
	end, err := dst.Seek(0, io.SeekCurrent)
	if err != nil {
		return err
	}

	meta, err := json.Marshal(map[string]any{"version": version, "files": files, "size": size})
	if err != nil {
		return err
	}
	foot := make([]byte, 12, 12+len(magic))
	binary.LittleEndian.PutUint64(foot[:8], uint64(end-start))
	binary.LittleEndian.PutUint32(foot[8:12], uint32(len(meta)))
	foot = append(foot, magic...)
	if _, err := dst.Write(append(meta, foot...)); err != nil {
		return err
	}
	log.Printf("%s: %d файлов, %d МБ -> %d МБ", out, files, size>>20, (end-start)>>20)
	return nil
}
