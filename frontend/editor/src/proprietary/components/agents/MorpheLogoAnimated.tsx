import "@app/components/chat/ChatPanel.css";

export function MorpheLogoAnimated({ size = 20 }: { size?: number }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width={size}
      height={size}
      viewBox="0 0 71 79"
      fill="currentColor"
      aria-hidden="true"
    >
      <path
        className="morphe-thinking__path-left"
        d="M4 60 L35.5 10 L35.5 26 L4 76 Z"
      />
      <path
        className="morphe-thinking__path-right"
        d="M67 60 L35.5 10 L35.5 26 L67 76 Z"
      />
    </svg>
  );
}
