"""Export the TV design study as one offline HTML file, including its images."""
import base64
import io
import mimetypes
from pathlib import Path
import re
from PIL import Image

root = Path(__file__).parent
source = (root / "tv-ui-prototype.template.html").read_text(encoding="utf-8")


def embed(match):
    image = root / "assets" / match.group(1)
    mime = mimetypes.guess_type(image.name)[0]
    data = image.read_bytes()
    if image.suffix == ".jpg" and len(data) > 45_000:
        picture = Image.open(image).convert("RGB")
        picture.thumbnail((1080, 1080))
        for quality in (78, 68, 58, 48, 38):
            buffer = io.BytesIO()
            picture.save(buffer, format="WEBP", quality=quality, method=6)
            data = buffer.getvalue()
            if len(data) <= 45_000:
                break
        mime = "image/webp"
    encoded = base64.b64encode(data).decode("ascii")
    return f'src="data:{mime};base64,{encoded}"'


html, count = re.subn(r'src="assets/([^"]+)"', embed, source)
if "assets/" in html or count != 14:
    raise SystemExit("Unembedded image reference or unexpected fixture count")
if len(html) > 500_000:
    raise SystemExit("Prototype exceeds the inline preview size budget")
output = root / "slinktv-ui-prototype.html"
output.write_text(html, encoding="utf-8")
print(f"Built {output}: {output.stat().st_size:,} bytes, {count} embedded images; no runtime dependencies.")
