"""Generate the TV launcher banner and the README logo from one drawing."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1]


def draw_banner(scale: int) -> Image.Image:
    image = Image.new("RGB", (320 * scale, 180 * scale), "#0b111b")
    draw = ImageDraw.Draw(image)
    s = lambda *values: tuple(v * scale for v in values)
    draw.rounded_rectangle(s(24, 56, 94, 110), radius=9 * scale, fill="#62e8bf")
    draw.polygon((s(51, 67), s(51, 99), s(77, 83)), fill="#0b111b")
    draw.rounded_rectangle(s(43, 117, 76, 121), radius=2 * scale, fill="#62e8bf")
    font = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 38 * scale)
    draw.text(s(111, 58), "SlinkTV", font=font, fill="white")
    small = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 15 * scale)
    draw.text(s(114, 106), "TWITCH ON YOUR TV", font=small, fill="#abbacc")
    return image


banner = root / "app/src/main/res/drawable-xhdpi/tv_banner.png"
banner.parent.mkdir(exist_ok=True)
draw_banner(1).save(banner)

logo = draw_banner(3).convert("RGBA")
mask = Image.new("L", logo.size, 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, *logo.size), radius=48, fill=255)
logo.putalpha(mask)
target = root / ".github/logo.png"
target.parent.mkdir(exist_ok=True)
logo.save(target, optimize=True)
