"""Generate the TV launcher banner with its readable application name."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1]
image = Image.new("RGB", (320, 180), "#0b111b")
draw = ImageDraw.Draw(image)
draw.rounded_rectangle((24, 56, 94, 110), radius=9, fill="#62e8bf")
draw.polygon(((51, 67), (51, 99), (77, 83)), fill="#0b111b")
draw.rounded_rectangle((43, 117, 76, 121), radius=2, fill="#62e8bf")
font = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 38)
draw.text((111, 58), "SlinkTV", font=font, fill="white")
small = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 15)
draw.text((114, 106), "TWITCH ON YOUR TV", font=small, fill="#abbacc")
target = root / "app/src/main/res/drawable-xhdpi/tv_banner.png"
target.parent.mkdir(exist_ok=True)
image.save(target)
