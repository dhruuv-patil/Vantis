import Image from "next/image";
import { cn } from "@/lib/utils";
import logoMark from "./logo-mark.svg";

export function LogoMark({
  className,
  size = 38,
}: {
  className?: string;
  size?: number;
}) {
  return (
    <Image
      src={logoMark}
      alt="Vantis"
      width={size}
  height={size}
  style={{
    width: `${size}px`,
    height: `${size}px`,
    minWidth: `${size}px`,
    minHeight: `${size}px`,
  }}
  className={cn("shrink-0 object-contain", className)}
  priority
/>
  );
}

export function Logo({
  className,
  size = 42,
}: {
  className?: string;
  size?: number;
}) {
  return (
    <span className={cn("inline-flex items-center gap-2.5", className)}>
      <LogoMark size={size} />

      <span className="text-xl font-semibold tracking-[-0.03em] text-foreground">
        Vantis
      </span>
    </span>
  );
}