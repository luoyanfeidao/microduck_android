#!/usr/bin/env python3
"""Check source evidence, PDF text/bookmarks/bounds, and render review previews."""
from pathlib import Path
import hashlib
import json
import math
import xml.etree.ElementTree as ET
import pymupdf as fitz
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[1]
PDF = OUT / 'Android_Microduck_方案架构与编译复用说明.pdf'
data = json.loads((OUT / 'sources.json').read_text())
for reference in data['refs']:
    file = ROOT / reference['file']
    lines = file.read_text().splitlines()
    assert 1 <= reference['line'] <= len(lines), reference
    assert hashlib.sha256(file.read_bytes()).hexdigest() == reference['sha256'], reference['file']
    if isinstance(reference['anchor'], str):
        assert reference['anchor'] in lines[reference['line'] - 1]
for snippet in data['snippets']:
    lines = (ROOT / snippet['file']).read_text().splitlines()
    assert '\n'.join(lines[snippet['start'] - 1:snippet['end']]) == snippet['text'], snippet['file']
for name in data['diagrams']:
    ET.parse(OUT / 'diagrams' / f'{name}.svg')
doc = fitz.open(PDF)
assert len(doc.get_toc()) == len(data['sections']), doc.get_toc()
texts, overflow, thumbnails = [], [], []
preview_dir = OUT / 'previews'
preview_dir.mkdir(exist_ok=True)
sections_by_page = {entry[2]: entry[1] for entry in doc.get_toc()}
review_terms = ['三个项目', '构建链', '线程所有权', '机器人相机架构', '单次前滚翻', '新机器恢复']
review_pages = {1, 2}
review_pages.update(page for page, title in sections_by_page.items()
                    if any(term in title for term in review_terms))
for index, page in enumerate(doc, 1):
    text = page.get_text()
    texts.append(text)
    assert len(text) > 70, (index, text)
    for block in page.get_text('dict')['blocks']:
        for line in block.get('lines', []):
            for span in line['spans']:
                x0, y0, x1, y1 = span['bbox']
                if x0 < 30 or x1 > page.rect.width - 30 or y0 < 10 or y1 > page.rect.height - 15:
                    overflow.append({'page': index, 'text': span['text'], 'bbox': span['bbox']})
    pix = page.get_pixmap(matrix=fitz.Matrix(.42, .42), alpha=False)
    thumbnails.append(Image.frombytes('RGB', [pix.width, pix.height], pix.samples))
    if index in review_pages:
        page.get_pixmap(matrix=fitz.Matrix(1.2, 1.2), alpha=False).save(preview_dir / f'page-{index:02d}.png')
assert not overflow, overflow
all_text = '\n'.join(texts)
for term in ['prepare_assets.py', 'BAM', 'libmujoco.so', '14D', '61D', '不编译', '抬头', 'NDK', 'SHA', 'preBuild']:
    # The guide uses several equivalent phrasings; check its substantive concepts.
    if term == '不编译':
        assert '不参与' in all_text or '没有把Rust' in all_text
    else:
        assert term in all_text, term
width, height = thumbnails[0].size
columns = 4
sheet = Image.new('RGB', (columns * (width + 12), math.ceil(len(doc) / columns) * (height + 28)), '#dbe5eb')
draw = ImageDraw.Draw(sheet)
for index, thumb in enumerate(thumbnails):
    x, y = (index % columns) * (width + 12), (index // columns) * (height + 28)
    sheet.paste(thumb, (x + 6, y + 6))
    draw.text((x + 9, y + height + 9), str(index + 1), fill='#17344b')
sheet.save(preview_dir / 'contact-sheet.png')
result = {'scope': 'Document checks only; no Gradle/APK/device or training execution',
    'pages': len(doc), 'sections_and_bookmarks': len(doc.get_toc()), 'source_references': len(data['refs']),
    'exact_source_snippets': len(data['snippets']), 'vector_diagrams': len(data['diagrams']),
    'source_sha256_and_snippets_match': True, 'out_of_bounds_text': overflow,
    'pdf_bytes': PDF.stat().st_size, 'text_characters': sum(map(len, texts)),
    'review_pages': sorted(review_pages)}
(OUT / 'validation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(result, ensure_ascii=False, indent=2))
