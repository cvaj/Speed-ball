#!/usr/bin/env python3
"""Quick fixed-camera motion-blob proof of concept.

Builds a per-pixel median background from a short window, diffs each frame
against that background, extracts moving blobs, and writes proof imagery.
This is intentionally a local POC script, not production Android code.
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path

import cv2
import numpy as np


def read_frames(input_path: Path, size: tuple[int, int]) -> tuple[list[str], list[np.ndarray], float | None]:
    frames: list[np.ndarray] = []
    names: list[str] = []
    fps: float | None = None
    if input_path.is_dir():
        files = sorted(
            p for p in input_path.iterdir()
            if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".bmp"}
        )
        for file in files:
            image = cv2.imread(str(file), cv2.IMREAD_COLOR)
            if image is None:
                continue
            frames.append(cv2.resize(image, size, interpolation=cv2.INTER_AREA))
            names.append(file.name)
    else:
        cap = cv2.VideoCapture(str(input_path))
        fps_value = cap.get(cv2.CAP_PROP_FPS)
        fps = fps_value if fps_value and math.isfinite(fps_value) else None
        index = 0
        while True:
            ok, image = cap.read()
            if not ok:
                break
            index += 1
            frames.append(cv2.resize(image, size, interpolation=cv2.INTER_AREA))
            names.append(f"frame-{index:04d}")
        cap.release()
    if not frames:
        raise SystemExit(f"No frames read from {input_path}")
    return names, frames, fps


def blob_rows(mask: np.ndarray, min_area: float) -> list[tuple[float, int, int, int, int, float, float, float]]:
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    rows = []
    for contour in contours:
        area = cv2.contourArea(contour)
        if area < min_area:
            continue
        moments = cv2.moments(contour)
        if moments["m00"] == 0:
            continue
        x, y, w, h = cv2.boundingRect(contour)
        cx = moments["m10"] / moments["m00"]
        cy = moments["m01"] / moments["m00"]
        perimeter = cv2.arcLength(contour, True)
        circularity = 4.0 * math.pi * area / (perimeter * perimeter) if perimeter else 0.0
        rows.append((area, x, y, w, h, cx, cy, circularity))
    rows.sort(key=lambda row: row[0], reverse=True)
    return rows


def make_sheet(paths: list[Path], output: Path, every: int = 1, cols: int = 4) -> None:
    selected = paths[::every]
    if not selected:
        return
    images = [cv2.imread(str(path), cv2.IMREAD_COLOR) for path in selected]
    images = [image for image in images if image is not None]
    if not images:
        return
    height, width = images[0].shape[:2]
    rows = math.ceil(len(images) / cols)
    sheet = np.zeros((rows * height, cols * width, 3), dtype=np.uint8)
    for index, image in enumerate(images):
        y = (index // cols) * height
        x = (index % cols) * width
        sheet[y:y + height, x:x + width] = image
        cv2.putText(
            sheet,
            str(index * every + 1),
            (x + 8, y + 24),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.8,
            (0, 255, 255),
            2,
            cv2.LINE_AA,
        )
    cv2.imwrite(str(output), sheet)


def estimate_velocity(points: list[tuple[int, float, float]], fps: float, frame_width_feet: float, width_px: int) -> str:
    if len(points) < 2:
        return "velocity=not-enough-points"
    frame_numbers = np.array([p[0] for p in points], dtype=float)
    t = (frame_numbers - frame_numbers[0]) / fps
    x = np.array([p[1] for p in points], dtype=float)
    y = np.array([p[2] for p in points], dtype=float)
    a = np.vstack([t, np.ones_like(t)]).T
    vx, bx = np.linalg.lstsq(a, x, rcond=None)[0]
    vy, by = np.linalg.lstsq(a, y, rcond=None)[0]
    residual = np.sqrt((x - (vx * t + bx)) ** 2 + (y - (vy * t + by)) ** 2)
    feet_per_px = frame_width_feet / width_px
    speed_mph = math.hypot(vx, vy) * feet_per_px * 3600.0 / 5280.0
    horizontal_mph = abs(vx) * feet_per_px * 3600.0 / 5280.0
    down_angle = math.degrees(math.atan2(vy, -vx if vx < 0 else vx))
    return (
        f"velocity totalMph={speed_mph:.2f} horizontalMph={horizontal_mph:.2f} "
        f"downAngleDeg={down_angle:.2f} rmsResidualPx={float(np.sqrt(np.mean(residual ** 2))):.2f} "
        f"maxResidualPx={float(np.max(residual)):.2f} points={len(points)}"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--out", type=Path, default=Path(".interagent/tmp/motion-blob-poc"))
    parser.add_argument("--width", type=int, default=640)
    parser.add_argument("--height", type=int, default=360)
    parser.add_argument("--threshold", type=int, default=18)
    parser.add_argument("--min-area", type=float, default=20.0)
    parser.add_argument("--open", type=int, default=3, dest="open_kernel")
    parser.add_argument("--close", type=int, default=9, dest="close_kernel")
    parser.add_argument("--close-iterations", type=int, default=2)
    parser.add_argument("--fps", type=float, default=None)
    parser.add_argument("--frame-width-feet", type=float, default=None)
    args = parser.parse_args()

    size = (args.width, args.height)
    names, frames, file_fps = read_frames(args.input, size)
    fps = args.fps or file_fps
    args.out.mkdir(parents=True, exist_ok=True)
    for old in args.out.glob("*"):
        if old.is_file():
            old.unlink()

    background = np.median(np.stack(frames, axis=0), axis=0).astype(np.uint8)
    cv2.imwrite(str(args.out / "median_background.jpg"), background)
    gray_background = cv2.cvtColor(background, cv2.COLOR_BGR2GRAY)
    open_kernel = np.ones((args.open_kernel, args.open_kernel), dtype=np.uint8)
    close_kernel = np.ones((args.close_kernel, args.close_kernel), dtype=np.uint8)

    report = [
        f"input={args.input}",
        f"frames={len(frames)} size={args.width}x{args.height} fps={fps}",
        (
            f"method=median-background gray absdiff threshold>{args.threshold} "
            f"open={args.open_kernel} close={args.close_kernel}x{args.close_iterations} minArea={args.min_area}"
        ),
    ]
    annotated_paths: list[Path] = []
    mask_paths: list[Path] = []
    primary_track: list[tuple[int, float, float]] = []

    for frame_index, (name, frame) in enumerate(zip(names, frames), start=1):
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        diff = cv2.absdiff(gray, gray_background)
        _, mask = cv2.threshold(diff, args.threshold, 255, cv2.THRESH_BINARY)
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, open_kernel, iterations=1)
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, close_kernel, iterations=args.close_iterations)
        blobs = blob_rows(mask, args.min_area)

        annotated = frame.copy()
        for blob_index, row in enumerate(blobs[:8], start=1):
            area, x, y, w, h, cx, cy, circularity = row
            color = (0, 0, 255) if blob_index == 1 else (255, 0, 0)
            cv2.rectangle(annotated, (x, y), (x + w, y + h), color, 2)
            cv2.circle(annotated, (int(cx), int(cy)), 4, (0, 255, 255), -1)
            cv2.putText(annotated, str(blob_index), (x, max(14, y - 4)), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1, cv2.LINE_AA)

        if blobs:
            _, _, _, _, _, cx, cy, _ = blobs[0]
            primary_track.append((frame_index, cx, cy))

        annotated_path = args.out / f"annotated-{frame_index:04d}.jpg"
        mask_path = args.out / f"mask-{frame_index:04d}.jpg"
        cv2.imwrite(str(annotated_path), annotated)
        cv2.imwrite(str(mask_path), mask)
        annotated_paths.append(annotated_path)
        mask_paths.append(mask_path)

        report.append(f"frame={frame_index:04d} name={name} motionPx={int(mask.sum() / 255)} blobs={len(blobs)}")
        for blob_index, row in enumerate(blobs[:5], start=1):
            area, x, y, w, h, cx, cy, circularity = row
            report.append(
                f"  #{blob_index} area={area:.1f} bbox={w}x{h}@{x},{y} "
                f"centroid=({cx:.1f},{cy:.1f}) circularity={circularity:.2f}"
            )

    if fps and args.frame_width_feet:
        report.append(estimate_velocity(primary_track, fps, args.frame_width_feet, args.width))
    elif primary_track:
        report.append(f"primaryTrackPoints={len(primary_track)} noVelocity=fps/frame-width-feet-not-both-set")

    make_sheet(annotated_paths, args.out / "annotated_contact_sheet.jpg", every=max(1, len(annotated_paths) // 12))
    make_sheet(mask_paths, args.out / "mask_contact_sheet.jpg", every=max(1, len(mask_paths) // 12))
    (args.out / "motion_blob_report.txt").write_text("\n".join(report) + "\n")
    print(args.out / "motion_blob_report.txt")


if __name__ == "__main__":
    main()
