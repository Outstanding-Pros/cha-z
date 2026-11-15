"""클립마다 균등 간격 20프레임을 뽑아 5x4 접촉 시트(타임스탬프 표기)를 만든다."""
import csv, subprocess, sys, tempfile
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
root = Path(__file__).resolve().parent.parent
ff = root / "scripts/ff.sh"
out = root / "analysis/sheets"; out.mkdir(parents=True, exist_ok=True)
font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 28)
N, COLS, W = 20, 5, 512
for name, dur, _ in csv.reader(open(root / "analysis/durations.csv")):
    dur = float(dur); tiles = []
    with tempfile.TemporaryDirectory() as td:
        for i in range(N):
            t = dur * (i + 0.5) / N
            p = Path(td) / f"{i}.png"
            subprocess.run([ff, "ffmpeg", "-v", "error", "-y", "-ss", f"{t:.2f}", "-i", str(root / f"public/clips/{name}.mp4"),
                            "-frames:v", "1", "-vf", f"scale={W}:-2", str(p)], check=True, stdin=subprocess.DEVNULL)
            im = Image.open(p).convert("RGB"); d = ImageDraw.Draw(im)
            d.rectangle([0, 0, 110, 36], fill="black"); d.text((6, 3), f"{t:5.1f}s", fill="yellow", font=font)
            tiles.append(im)
    h = tiles[0].height; rows = (N + COLS - 1) // COLS
    sheet = Image.new("RGB", (COLS * (W + 4), rows * (h + 4)), "white")
    for i, im in enumerate(tiles):
        sheet.paste(im, ((i % COLS) * (W + 4), (i // COLS) * (h + 4)))
    sheet.save(out / f"{name}.jpg", quality=80); print("ok", name)
