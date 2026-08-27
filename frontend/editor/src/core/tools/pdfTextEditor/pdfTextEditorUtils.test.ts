import { describe, expect, it } from "vitest";

import { restoreGlyphElements } from "@app/tools/pdfTextEditor/pdfTextEditorUtils";
import type {
  PdfJsonDocument,
  PdfJsonTextElement,
  TextGroup,
} from "@app/tools/pdfTextEditor/pdfTextEditorTypes";

/**
 * Geometry regressions for the editor's paragraph re-layout.
 *
 * The Java-side {@code PdfJsonRoundTripFidelityTest} only covers an unedited
 * PDF -> JSON -> PDF trip, so it cannot see these: the re-layout that runs when
 * a user actually types lives here in the frontend.
 */

const FONT_SIZE = 14;

/**
 * Builds a text matrix for a single run rotated by `degrees` about (x, y).
 * Mirrors what PDFBox writes for rotated text: the rotation is baked into the
 * a/b/c/d cells and scaled by the font size.
 */
const rotatedMatrix = (degrees: number, x: number, y: number): number[] => {
  const radians = (degrees * Math.PI) / 180;
  const cos = Math.cos(radians);
  const sin = Math.sin(radians);
  return [
    FONT_SIZE * cos,
    FONT_SIZE * sin,
    -FONT_SIZE * sin,
    FONT_SIZE * cos,
    x,
    y,
  ];
};

const textElement = (text: string, matrix: number[]): PdfJsonTextElement => ({
  text,
  fontId: "F1",
  fontSize: FONT_SIZE,
  fontMatrixSize: FONT_SIZE,
  x: matrix[4],
  y: matrix[5],
  width: 60,
  height: FONT_SIZE,
  textMatrix: matrix,
});

/** A single-line group whose text has been edited to add a second line. */
const editedTwoLineGroup = (matrix: number[]): TextGroup => {
  const element = textElement("Line one", matrix);
  return {
    id: "0-0",
    pageIndex: 0,
    fontId: "F1",
    fontSize: FONT_SIZE,
    fontMatrixSize: FONT_SIZE,
    elements: [element],
    originalElements: [textElement("Line one", [...matrix])],
    text: "Line one\nLine two",
    originalText: "Line one",
    bounds: {
      left: matrix[4],
      right: matrix[4] + 60,
      top: matrix[5],
      bottom: matrix[5],
    },
  };
};

const documentWith = (element: PdfJsonTextElement): PdfJsonDocument => ({
  pages: [
    {
      pageNumber: 1,
      width: 612,
      height: 792,
      textElements: [element],
      imageElements: [],
    },
  ],
});

const rebuild = (matrix: number[]): PdfJsonTextElement[] => {
  const group = editedTwoLineGroup(matrix);
  const result = restoreGlyphElements(
    documentWith(group.elements[0]),
    [[group]],
    [[]],
    [[]],
  );
  return result.pages?.[0]?.textElements ?? [];
};

describe("paragraph re-layout geometry", () => {
  it("advances upright text straight down the page", () => {
    const elements = rebuild(rotatedMatrix(0, 160, 250));

    expect(elements).toHaveLength(2);
    const [first, second] = elements;

    // Unchanged behaviour for the common case: same x, lower y.
    expect(second.textMatrix?.[4]).toBeCloseTo(first.textMatrix?.[4] ?? 0, 5);
    expect(second.textMatrix?.[5]).toBeLessThan(first.textMatrix?.[5] ?? 0);
  });

  it("advances 90-degree rotated text along its own axis, not down the page", () => {
    const elements = rebuild(rotatedMatrix(90, 160, 250));

    expect(elements).toHaveLength(2);
    const [first, second] = elements;

    // For text rotated a quarter turn the next line sits beside the first, at
    // the same height. Shifting in Y instead would walk it off the baseline.
    expect(second.textMatrix?.[5]).toBeCloseTo(first.textMatrix?.[5] ?? 0, 5);
    expect(second.textMatrix?.[4]).not.toBeCloseTo(
      first.textMatrix?.[4] ?? 0,
      1,
    );
  });

  it("keeps rotated line advance perpendicular to the baseline", () => {
    const degrees = 30;
    const elements = rebuild(rotatedMatrix(degrees, 160, 250));

    expect(elements).toHaveLength(2);
    const [first, second] = elements;

    const deltaX = (second.textMatrix?.[4] ?? 0) - (first.textMatrix?.[4] ?? 0);
    const deltaY = (second.textMatrix?.[5] ?? 0) - (first.textMatrix?.[5] ?? 0);

    // The advance must be parallel to the text's "up" axis, so its component
    // along the baseline direction is zero - that dot product is exactly the
    // sideways drift users had to correct by hand.
    const radians = (degrees * Math.PI) / 180;
    const baselineDrift =
      deltaX * Math.cos(radians) + deltaY * Math.sin(radians);

    expect(baselineDrift).toBeCloseTo(0, 5);
    expect(Math.hypot(deltaX, deltaY)).toBeGreaterThan(0);
  });
});

/** A two-line paragraph split across per-word elements, as the grouper produces. */
const editedParagraphGroup = (newText: string): TextGroup => {
  const words = ["Alpha ", "beta ", "gamma"];
  let x = 100;
  const build = (text: string, baselineY: number) => {
    const element = textElement(text, [
      FONT_SIZE,
      0,
      0,
      FONT_SIZE,
      x,
      baselineY,
    ]);
    x += text.length * 7;
    return element;
  };
  const line1 = words.map((w) => build(w, 300));
  x = 100;
  const line2 = words.map((w) => build(w, 300 - 16));
  const all = [...line1, ...line2];
  return {
    id: "0-0",
    pageIndex: 0,
    fontId: "F1",
    fontSize: FONT_SIZE,
    fontMatrixSize: FONT_SIZE,
    lineElementCounts: [line1.length, line2.length],
    elements: all.map((e) => ({ ...e })),
    originalElements: all.map((e) => ({ ...e })),
    text: newText,
    originalText: "Alpha beta gamma\nAlpha beta gamma",
    bounds: { left: 100, right: 300, top: 300, bottom: 284 },
  };
};

describe("paragraph re-layout spacing", () => {
  it("keeps original glyph positions when a line's length is unchanged", () => {
    const group = editedParagraphGroup("Alpbb beta gamma\nAlpha beta gamma");
    const result = restoreGlyphElements(
      documentWith(group.elements[0]),
      [[group]],
      [[]],
      [[]],
    );
    const elements = result.pages?.[0]?.textElements ?? [];

    // Same length, so each word keeps the x it was extracted at.
    const xs = elements.filter((e) => e.text).map((e) => e.textMatrix?.[4]);
    expect(xs).toContain(100);
    expect(new Set(xs).size).toBeGreaterThan(1);
  });

  it("does not pack a longer line into the original glyph slots", () => {
    const group = editedParagraphGroup(
      "Alpha beta gamma delta epsilon zeta\nAlpha beta gamma",
    );
    const result = restoreGlyphElements(
      documentWith(group.elements[0]),
      [[group]],
      [[]],
      [[]],
    );
    const elements = result.pages?.[0]?.textElements ?? [];

    const firstLine = elements.filter(
      (e) => Math.abs((e.textMatrix?.[5] ?? 0) - 300) < 0.5,
    );
    const withText = firstLine.filter((e) => (e.text ?? "").length > 0);

    // The lengthened line must be carried by a single run starting at the line origin, not
    // chopped across the old per-word positions - that is what produced overlapping text.
    expect(withText).toHaveLength(1);
    expect(withText[0].text).toBe("Alpha beta gamma delta epsilon zeta");
    expect(withText[0].textMatrix?.[4]).toBeCloseTo(100, 5);
  });
});
