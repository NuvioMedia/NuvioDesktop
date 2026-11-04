import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

BLACK = (8, 8, 8, 255)
GOLD = (215, 182, 93, 255)
GOLD_LIGHT = (240, 217, 140, 255)
FONT = "/usr/share/fonts/truetype/google-fonts/Poppins-Bold.ttf"
SS = 4  # supersampling

def gold_gradient(size):
    w, h = size
    img = Image.new("RGBA", size)
    px = img.load()
    for y in range(h):
        t = y / max(h - 1, 1)
        c = tuple(int(GOLD_LIGHT[i] * (1 - t) + GOLD[i] * t) for i in range(3)) + (255,)
        for x in range(w):
            px[x, y] = c
    return img

def render(size, background=True, text_ratio=0.62, round_corner=0.0):
    s = size * SS
    canvas = Image.new("RGBA", (s, s), BLACK if background else (0, 0, 0, 0))
    font_size = 10
    # fit text width to text_ratio of canvas
    while True:
        f = ImageFont.truetype(FONT, font_size)
        bbox = f.getbbox("DTV")
        if bbox[2] - bbox[0] >= s * text_ratio or font_size > s:
            break
        font_size += 2
    f = ImageFont.truetype(FONT, font_size)
    bbox = f.getbbox("DTV")
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    mask = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(mask)
    d.text(((s - tw) / 2 - bbox[0], (s - th) / 2 - bbox[1]), "DTV", font=f, fill=255)
    canvas.paste(gold_gradient((s, s)), (0, 0), mask)
    if round_corner > 0 and background:
        m = Image.new("L", (s, s), 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * round_corner), fill=255)
        out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        out.paste(canvas, (0, 0), m)
        canvas = out
    return canvas.resize((size, size), Image.LANCZOS)

def wordmark(width, height, background=False):
    s_w, s_h = width * SS, height * SS
    canvas = Image.new("RGBA", (s_w, s_h), BLACK if background else (0, 0, 0, 0))
    font_size = 10
    while True:
        f = ImageFont.truetype(FONT, font_size)
        bbox = f.getbbox("DTV")
        if bbox[3] - bbox[1] >= s_h * 0.62 or font_size > s_h * 2:
            break
        font_size += 2
    f = ImageFont.truetype(FONT, font_size)
    bbox = f.getbbox("DTV")
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    mask = Image.new("L", (s_w, s_h), 0)
    ImageDraw.Draw(mask).text(((s_w - tw) / 2 - bbox[0], (s_h - th) / 2 - bbox[1]), "DTV", font=f, fill=255)
    canvas.paste(gold_gradient((s_w, s_h)), (0, 0), mask)
    return canvas.resize((width, height), Image.LANCZOS)

if __name__ == "__main__":
    out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    render(1024).save(out / "dtv-icon-1024.png")
    render(1024, background=False).save(out / "dtv-logo-transparent-1024.png")
    wordmark(1200, 400).save(out / "dtv-logo-wordmark-transparent.png")
    wordmark(1200, 400, background=True).save(out / "dtv-logo-wordmark-black.png")
    print("ok")
