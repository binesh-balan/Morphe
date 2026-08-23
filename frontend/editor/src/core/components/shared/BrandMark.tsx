import "@app/components/shared/BrandMark.css";

interface BrandMarkProps {
  /** Height of the mark (CSS length). */
  height?: string;
  className?: string;
}

/**
 * The Morphe PDF logo mark as inline SVG so it can morph. At rest the two
 * parallelogram arms meet in an upward caret — the brand mark. When an ancestor
 * marked `[data-brandmark-morph]` is hovered / focused / open (`.is-open`), the
 * same two arms slide into a smaller downward chevron in the primary text
 * colour — a self-explaining "this opens a menu" affordance, and the reason the
 * mark is built from two parallelograms: the name made literal. See
 * BrandMark.css for the morph geometry.
 */
export function BrandMark({ height = "1.6rem", className }: BrandMarkProps) {
  return (
    <svg
      className={`sui-brandmark${className ? ` ${className}` : ""}`}
      viewBox="0 0 71 79"
      style={{ height }}
      role="img"
      aria-label="Morphe PDF"
      xmlns="http://www.w3.org/2000/svg"
    >
      <path className="sui-brandmark__a" d="M4 60 L35.5 10 L35.5 26 L4 76 Z" />
      <path
        className="sui-brandmark__b"
        d="M67 60 L35.5 10 L35.5 26 L67 76 Z"
      />
    </svg>
  );
}
