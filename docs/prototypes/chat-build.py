"""Build the offline chat study. Does not touch shared prototype/build.py."""
import base64, json
from pathlib import Path
from PIL import Image

root = Path(__file__).parent
assets = root / 'chat-assets'
scene = assets / 'scene.jpg'
with Image.open(root / 'assets/eldenring.jpg') as image:
    image.thumbnail((1200, 675))
    image.convert('RGB').save(scene, quality=78, optimize=True)
data = {}
for item in json.loads((assets / 'attribution.json').read_text()):
    name = item['name']
    data[name] = dict(src='data:image/webp;base64,' + base64.b64encode((assets / (name+'.webp')).read_bytes()).decode(),
                      width=item['file']['width'], height=item['file']['height'], page=item['page'])
html = (root / 'chat-ui.template.html').read_text(encoding='utf-8')
html = html.replace('{{SCENE}}', 'data:image/jpeg;base64,'+base64.b64encode(scene.read_bytes()).decode())
html = html.replace('{{EMOTES}}', json.dumps(data))
assert len(html) < 490000, len(html)
(root / 'chat-ui-prototype.html').write_text(html, encoding='utf-8', newline='\n')
print(f'chat-ui-prototype.html: {len(html):,} characters, {len(html.encode()):,} bytes')
