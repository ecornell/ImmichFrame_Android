#!/usr/bin/env python3
"""Detect a consistent one-pixel vertical seam in an ADB RGBA/RGB PNG screenshot."""

import argparse
import json
import pathlib
import struct
import sys
import zlib


def read_png(path: pathlib.Path):
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos, width, height, color, payload = 8, 0, 0, 0, bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind, body = data[pos + 4:pos + 8], data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if depth != 8 or color not in (2, 6) or interlace:
                raise ValueError("only non-interlaced 8-bit RGB/RGBA PNG is supported")
        elif kind == b"IDAT":
            payload.extend(body)
        elif kind == b"IEND":
            break
    channels = 4 if color == 6 else 3
    raw, stride, rows, offset = zlib.decompress(bytes(payload)), width * channels, [], 0
    prior = bytearray(stride)
    for _ in range(height):
        mode, scan = raw[offset], bytearray(raw[offset + 1:offset + 1 + stride]); offset += stride + 1
        for i in range(stride):
            left = scan[i - channels] if i >= channels else 0
            up = prior[i]
            upper_left = prior[i - channels] if i >= channels else 0
            if mode == 1: scan[i] = (scan[i] + left) & 255
            elif mode == 2: scan[i] = (scan[i] + up) & 255
            elif mode == 3: scan[i] = (scan[i] + ((left + up) // 2)) & 255
            elif mode == 4:
                p = left + up - upper_left; pa, pb, pc = abs(p-left), abs(p-up), abs(p-upper_left)
                scan[i] = (scan[i] + (left if pa <= pb and pa <= pc else up if pb <= pc else upper_left)) & 255
            elif mode != 0: raise ValueError(f"unsupported PNG filter {mode}")
        rows.append(scan); prior = scan
    return width, height, channels, rows


def column_score(rows, channels, x):
    values = []
    for row in rows:
        a, b, c = x * channels, (x - 1) * channels, (x + 1) * channels
        values.append(sum(abs(row[a+i] - ((row[b+i] + row[c+i]) / 2)) for i in range(3)) / 3)
    values.sort()
    return sum(values[len(values)//4:3*len(values)//4]) / max(1, len(values)//2)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("screenshot", type=pathlib.Path)
    parser.add_argument("--edge", action="append", type=int)
    parser.add_argument("--threshold", type=float, default=12.0)
    parser.add_argument("--outer-fraction", type=float, default=0.2)
    args = parser.parse_args()
    width, height, channels, rows = read_png(args.screenshot)
    edges = args.edge or list(range(2, max(3, int(width * args.outer_fraction)))) + \
        list(range(min(width - 2, int(width * (1 - args.outer_fraction))), width - 2))
    scores = [{"x": x, "score": round(column_score(rows, channels, x), 3)} for x in edges if 1 <= x < width - 1]
    scores.sort(key=lambda item: item["score"], reverse=True)
    report = {"schemaVersion": 1, "width": width, "height": height,
              "threshold": args.threshold, "candidates": scores[:10],
              "seamDetected": bool(scores and scores[0]["score"] >= args.threshold)}
    print(json.dumps(report, indent=2))
    return 1 if report["seamDetected"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
