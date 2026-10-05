"use client";

import * as React from "react";
import { BrandIcon } from "@/components/app/builder/node-visuals";

export interface TheSvgIconProps {
  icon?: string;
  className?: string;
  theme?: "dark" | "light" | "auto";
}

export function TheSvgIcon({
  icon,
  className = "size-5",
  theme = "auto",
}: TheSvgIconProps) {
  return <BrandIcon icon={icon} className={className} theme={theme} />;
}