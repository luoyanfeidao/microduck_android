#!/usr/bin/env python3
"""Build the Android technical guide from local source evidence, without building an APK."""
from pathlib import Path
from html import escape
import hashlib
import json
import os
import subprocess

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import BaseDocTemplate, Frame, PageTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle
from reportlab.platypus.tableofcontents import TableOfContents

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[1]
A, R, T = 'android-sim/', 'Pollen/microduck/', 'Pollen/microduck_rl/'
PREFIXES = {'A': A, 'R': R, 'T': T, 'M': A + '.native-cache/mujoco-3.10.0/'}
NAME = 'Android_Microduck_方案架构与编译复用说明'
WIDTH, HEIGHT = A4
MARGIN = 43
CW = WIDTH - 2 * MARGIN
NAVY, TEAL = colors.HexColor('#17344b'), colors.HexColor('#087f8c')
LIGHT, INK = colors.HexColor('#edf4f7'), colors.HexColor('#263746')
font = os.environ.get('MICRODUCK_DOC_FONT', '/Library/Fonts/Arial Unicode.ttf')
mono = os.environ.get('MICRODUCK_DOC_MONO', '/System/Library/Fonts/Supplemental/Andale Mono.ttf')
pdfmetrics.registerFont(TTFont('CN', font))
pdfmetrics.registerFont(TTFont('Mono', mono))
REFS, SNIPPETS, SECTIONS = {}, [], []
CURRENT = None


def path_for(path):
    if len(path) > 2 and path[1] == '/':
        return PREFIXES[path[0]] + path[2:]
    return path


def locate(path, anchor):
    full = path_for(path)
    lines = (ROOT / full).read_text().splitlines()
    if isinstance(anchor, int):
        line = anchor
    else:
        matches = [i + 1 for i, value in enumerate(lines) if anchor in value]
        if len(matches) != 1:
            raise ValueError(f'Expected unique anchor {full}: {anchor!r}; found {matches}')
        line = matches[0]
    assert 1 <= line <= len(lines), (full, line)
    REFS[(full, line)] = {'file': full, 'line': line, 'anchor': anchor,
        'sha256': hashlib.sha256((ROOT / full).read_bytes()).hexdigest()}
    return full, line


def ref(path, anchor=1):
    _, line = locate(path, anchor)
    return f'{path}:{line}'


def section(title, subtitle=''):
    global CURRENT
    CURRENT = {'title': title, 'subtitle': subtitle, 'items': []}
    SECTIONS.append(CURRENT)


def item(kind, value):
    CURRENT['items'].append((kind, value))


def p(value): item('p', value)
def h(value): item('h', value)
def note(value): item('note', value)
def table(headers, rows, widths=None): item('table', (headers, rows, widths))
def diagram(name, caption): item('diagram', (name, caption))
def command(value, where): item('command', (where, value))


def code(path, anchor, count):
    full, start = locate(path, anchor)
    lines = (ROOT / full).read_text().splitlines()
    assert start + count - 1 <= len(lines)
    excerpt = '\n'.join(lines[start - 1:start + count - 1])
    SNIPPETS.append({'file': full, 'start': start, 'end': start + count - 1, 'text': excerpt})
    numbered = '\n'.join(f'{i:4d}  {lines[i - 1]}'.rstrip() for i in range(start, start + count))
    item('code', (f'{path}:{start}', numbered))


def git_head(folder):
    return subprocess.check_output(['git', '-C', str(ROOT / folder), 'rev-parse', 'HEAD'], text=True).strip()


def para(value, size=10, leading=15, color=INK):
    return Paragraph(escape(str(value)).replace('\n', '<br/>'), ParagraphStyle('body',
        fontName='CN', fontSize=size, leading=leading, textColor=color,
        wordWrap='CJK', splitLongWords=True, spaceAfter=8))


class GuideDoc(BaseDocTemplate):
    def __init__(self, path):
        super().__init__(str(path), pagesize=A4, leftMargin=MARGIN, rightMargin=MARGIN,
            topMargin=54, bottomMargin=53, title='Android Microduck：方案、架构与编译复用',
            author='Microduck 工作区技术文档', pageCompression=1)
        self.addPageTemplates(PageTemplate('guide', Frame(MARGIN, 53, CW, HEIGHT - 107,
            leftPadding=0, bottomPadding=0, rightPadding=0, topPadding=0), onPage=self.decorate))

    def decorate(self, canvas, doc):
        canvas.setFillColor(NAVY)
        canvas.rect(0, HEIGHT - 8, WIDTH, 8, fill=1, stroke=0)
        canvas.setFont('CN', 8)
        canvas.setFillColor(TEAL)
        canvas.drawString(MARGIN, HEIGHT - 32, 'Android Microduck · M10 · 编译复用与技术实现')
        canvas.setStrokeColor(colors.HexColor('#d5e0e6'))
        canvas.line(MARGIN, 40, WIDTH - MARGIN, 40)
        canvas.setFont('CN', 7)
        canvas.drawString(MARGIN, 26, '2026-10-05 | A/ Android · R/ runtime · T/ RL · M/ MuJoCo缓存')
        canvas.drawRightString(WIDTH - MARGIN, 26, str(doc.page))

    def afterFlowable(self, flowable):
        if isinstance(flowable, Paragraph) and getattr(flowable, 'guide_key', None):
            key = flowable.guide_key
            text = flowable.getPlainText()
            self.canv.bookmarkPage(key)
            self.canv.addOutlineEntry(text, key, 0, False)
            self.notify('TOCEntry', (0, text, self.page, key))


def code_flow(label, value):
    lines = []
    for source in value.splitlines():
        # Wrap by actual font width; preserve the source line number on the first row.
        acc = ''
        for ch in source:
            if pdfmetrics.stringWidth(acc + ch, 'CN', 8) > CW - 20:
                lines.append(acc)
                acc = '      ' + ch
            else:
                acc += ch
        lines.append(acc)
    rows = [[para(label, 8, 11, TEAL)], [para('\n'.join(lines), 8, 11)]]
    block = Table(rows, colWidths=[CW], hAlign='LEFT')
    block.setStyle(TableStyle([('BACKGROUND', (0, 0), (-1, -1), LIGHT),
        ('LEFTPADDING', (0, 0), (-1, -1), 10), ('RIGHTPADDING', (0, 0), (-1, -1), 10),
        ('TOPPADDING', (0, 0), (-1, -1), 5), ('BOTTOMPADDING', (0, 0), (-1, -1), 5)]))
    return [block, Spacer(1, 10)]


def main():
    from content import write_content
    from diagrams import make_diagrams
    write_content(globals())
    diagrams = make_diagrams(CW, OUT)
    # Full paths and all cited offsets stay searchable in the appendix and JSON.
    section('附录：源码路径与证据索引', '行号对应本地快照；代码摘录与源码摘要一起保存。')
    p('sources.json保存每处引用的完整路径、定位锚点、行号与SHA-256，以及所有逐字源码摘录。构建脚本自动检查锚点唯一；解释文字仍需在源码更新后人工复核。上游未跟踪ONNX的训练run/checkpoint来源尚未补齐，本文不据目录名推断其训练历史。')
    table(['缩写', '工作区相对根目录'], [[key + '/', value] for key, value in PREFIXES.items()], [35, 474])
    groups = {}
    for full, line in REFS:
        groups.setdefault(full, []).append(line)
    table(['文件（工作区相对路径）', '引用行号'],
        [[file, ', '.join(str(n) for n in sorted(set(lines)))] for file, lines in sorted(groups.items())], [400, 109])
    story, markdown = [], ['# Android Microduck：方案、技术架构与编译复用说明\n']
    toc = TableOfContents()
    toc.levelStyles = [ParagraphStyle('toc', fontName='CN', fontSize=9.5, leading=17,
        textColor=INK, leftIndent=0, firstLineIndent=0, wordWrap='CJK', spaceBefore=0)]
    toc.tableStyle = TableStyle([('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 0), ('RIGHTPADDING', (0, 0), (-1, -1), 0),
        ('TOPPADDING', (0, 0), (-1, -1), 2), ('BOTTOMPADDING', (0, 0), (-1, -1), 2)])
    cover = para('Android Microduck\n方案、架构与编译复用', 28, 39, NAVY)
    story.extend([Spacer(1, 58), cover, Spacer(1, 16),
        para('从资源来源到 APK，从策略输入到机器人相机', 14, 22, TEAL), Spacer(1, 20),
        para('核对日期：2026-10-05\n当前 Android：0.10.0-m10 / versionCode 18\n三项目边界、构建链、关键代码、运行方法与验收证据。', 11, 19),
        Spacer(1, 15), diagrams['reuse'], Spacer(1, 15),
        para('A/ = android-sim/；R/ = Pollen/microduck/；T/ = Pollen/microduck_rl/。\n全文基于本地源码；示例命令没有在本轮执行。用户继续使用 Android Studio 构建和运行。', 9, 14),
        PageBreak(), para('目录', 22, 30, NAVY), toc, PageBreak()])
    for number, sec in enumerate(SECTIONS, 1):
        heading = para(f'{number:02d}  {sec["title"]}', 18, 26, NAVY)
        heading.guide_key = f'section-{number}'
        story.append(heading)
        if sec['subtitle']:
            story.append(para(sec['subtitle'], 9.5, 14, TEAL))
        markdown.extend([f'\n## {number:02d} {sec["title"]}\n', sec['subtitle'] + '\n'])
        for kind, value in sec['items']:
            if kind in ('p', 'h', 'note'):
                story.append(para(value, 12 if kind == 'h' else 9.5 if kind == 'note' else 10,
                    18 if kind == 'h' else 14 if kind == 'note' else 15,
                    TEAL if kind == 'h' else colors.HexColor('#98631b') if kind == 'note' else INK))
                markdown.append(('### ' if kind == 'h' else '> ' if kind == 'note' else '') + value + '\n')
            elif kind in ('code', 'command'):
                label, text = value
                story.extend(code_flow(('源码：' if kind == 'code' else '执行位置：') + label, text))
                markdown.extend([('来源：' if kind == 'code' else '执行位置：') + label,
                    '\n```' + ('bash' if kind == 'command' else ''), text, '```\n'])
            elif kind == 'diagram':
                name, caption = value
                story.extend([diagrams[name], Spacer(1, 8), para(caption, 8.5, 12, TEAL)])
                markdown.append(f'![{caption}](diagrams/{name}.svg)\n')
            elif kind == 'table':
                headers, rows, widths = value
                widths = widths or [CW / len(headers)] * len(headers)
                widths = [CW * w / sum(widths) for w in widths]
                cells = [[para(cell, 8.4, 12, colors.white) for cell in headers]]
                cells.extend([[para(cell, 8.5, 12.5) for cell in row] for row in rows])
                tb = Table(cells, colWidths=widths, repeatRows=1, hAlign='LEFT')
                row_padding = 3 if sec['title'].startswith('附录') else 5
                tb.setStyle(TableStyle([('BACKGROUND', (0, 0), (-1, 0), NAVY),
                    ('ROWBACKGROUNDS', (0, 1), (-1, -1), [LIGHT, colors.white]),
                    ('VALIGN', (0, 0), (-1, -1), 'TOP'),
                    ('LEFTPADDING', (0, 0), (-1, -1), 7), ('RIGHTPADDING', (0, 0), (-1, -1), 7),
                    ('TOPPADDING', (0, 0), (-1, -1), row_padding),
                    ('BOTTOMPADDING', (0, 0), (-1, -1), row_padding),
                    ('LINEBELOW', (0, 1), (-1, -1), .4, colors.HexColor('#d5e0e6'))]))
                story.extend([tb, Spacer(1, 10)])
                def cell_md(cell): return str(cell).replace('|', '\\|').replace('\n', '<br>')
                markdown.extend(['| ' + ' | '.join(headers) + ' |',
                    '| ' + ' | '.join(['---'] * len(headers)) + ' |'])
                markdown.extend('| ' + ' | '.join(cell_md(cell) for cell in row) + ' |' for row in rows)
                markdown.append('')
        if number != len(SECTIONS):
            story.append(PageBreak())
    GuideDoc(OUT / (NAME + '.pdf')).multiBuild(story)
    (OUT / (NAME + '.md')).write_text('\n'.join(markdown))
    snapshot = {'date': '2026-10-05', 'android_source_base_commit': git_head('android-sim'),
        'android_version': '0.10.0-m10', 'android_snapshot': 'working tree with source hashes',
        'microduck_commit': git_head(R), 'microduck_rl_commit': git_head(T),
        'scope': 'Local source and prior evidence, not new Gradle/APK/training execution',
        'refs': list(REFS.values()), 'snippets': SNIPPETS,
        'sections': [sec['title'] for sec in SECTIONS], 'diagrams': list(diagrams)}
    (OUT / 'sources.json').write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'pdf': str(OUT / (NAME + '.pdf')), 'sections': len(SECTIONS),
        'refs': len(REFS), 'snippets': len(SNIPPETS), 'diagrams': len(diagrams)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
