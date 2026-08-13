from __future__ import annotations

from pathlib import Path
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import PageBreak, Paragraph, Preformatted, SimpleDocTemplate, Spacer

ROOT = Path(r"E:\dev\WetInk-Next")
OUTPUT = ROOT / "output" / "pdf" / "wetink-next-source-p0-p32-current.pdf"
FONT = Path(r"C:\Windows\Fonts\consola.ttf")
INCLUDE_SUFFIXES = {".kt", ".xml", ".kts", ".properties"}
INCLUDE_NAMES = {"gradlew", "gradlew.bat"}
EXCLUDE_PARTS = {"build", ".gradle", ".idea", ".kotlin", "output", "tmp"}


def source_files() -> list[Path]:
    files: list[Path] = []
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(ROOT)
        if any(part in EXCLUDE_PARTS for part in relative.parts):
            continue
        if path.suffix in INCLUDE_SUFFIXES or path.name in INCLUDE_NAMES:
            files.append(path)
    return sorted(files, key=lambda path: path.relative_to(ROOT).as_posix())


def footer(canvas, doc) -> None:
    canvas.saveState()
    canvas.setFont("Consolas", 7)
    canvas.setFillColor(colors.HexColor("#6E6070"))
    canvas.drawString(16 * mm, 11 * mm, "WetInk Next - source snapshot P0-P32")
    canvas.drawRightString(A4[0] - 16 * mm, 11 * mm, f"Page {doc.page}")
    canvas.restoreState()


def main() -> None:
    pdfmetrics.registerFont(TTFont("Consolas", str(FONT)))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(
        str(OUTPUT), pagesize=A4,
        leftMargin=14 * mm, rightMargin=14 * mm,
        topMargin=15 * mm, bottomMargin=17 * mm,
        title="WetInk Next - source snapshot P0-P32", author="WetInk Next",
    )
    styles = getSampleStyleSheet()
    title = ParagraphStyle(
        "Title", parent=styles["Title"], fontName="Consolas", fontSize=18,
        leading=23, alignment=TA_CENTER, textColor=colors.HexColor("#342A38"),
    )
    heading = ParagraphStyle(
        "Heading", parent=styles["Heading2"], fontName="Consolas", fontSize=9,
        leading=12, textColor=colors.HexColor("#A22D72"), spaceBefore=2, spaceAfter=5,
    )
    body = ParagraphStyle(
        "Body", parent=styles["BodyText"], fontName="Consolas", fontSize=8,
        leading=11, textColor=colors.HexColor("#342A38"),
    )
    code = ParagraphStyle(
        "Code", parent=styles["Code"], fontName="Consolas", fontSize=6.5,
        leading=8.0, textColor=colors.HexColor("#201B22"),
    )

    files = source_files()
    story = [
        Spacer(1, 35 * mm),
        Paragraph("WetInk Next", title),
        Spacer(1, 5 * mm),
        Paragraph("Полный снимок исходного кода: P0-P32", title),
        Spacer(1, 12 * mm),
        Paragraph(
            "P32: документы проектов, autosave изменённых tile, GPU-превью слоёв и проекта, "
            "файловое хранилище с миграцией старых .wetink и дублирование проектов. "
            "Снимок включает движок, UI, persistence и unit-тесты текущей рабочей копии.",
            body,
        ),
        Spacer(1, 5 * mm),
        Paragraph(f"Включено файлов: {len(files)}. Сгенерировано из текущей рабочей копии проекта.", body),
        PageBreak(),
    ]
    for number, path in enumerate(files, start=1):
        relative = path.relative_to(ROOT).as_posix()
        story.append(Paragraph(f"{number}. {escape(relative)}", heading))
        text = path.read_text(encoding="utf-8", errors="replace")
        numbered = "\n".join(
            f"{line_number:4d}  {line}"
            for line_number, line in enumerate(text.splitlines(), 1)
        )
        story.append(Preformatted(numbered or "(empty file)", code, maxLineLength=132))
        story.append(PageBreak())

    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    print(OUTPUT)


if __name__ == "__main__":
    main()
