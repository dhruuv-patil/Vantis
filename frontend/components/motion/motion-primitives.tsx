"use client";

import { forwardRef, type CSSProperties, type ReactNode } from "react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { useReducedMotion } from "./use-reduced-motion";

const EASE = [0.25, 0.46, 0.45, 0.94] as const;

/**
 * Reveal — fade + rise on scroll into view.
 * Use for page sections, cards, hero elements.
 */
interface RevealProps {
  children: ReactNode;
  delay?: number;
  offset?: number;
  once?: boolean;
  distance?: number;
  className?: string;
  style?: CSSProperties;
}

export const Reveal = forwardRef<HTMLDivElement, RevealProps>(
  ({ children, delay = 0, offset = 100, once = true, distance = 20, className, style }, ref) => {
    const prefersReduced = useReducedMotion();

    if (prefersReduced) {
      return (
        <div ref={ref} className={className} style={style}>
          {children}
        </div>
      );
    }

    return (
      <motion.div
        ref={ref}
        initial={{ opacity: 0, y: distance }}
        whileInView={{ opacity: 1, y: 0 }}
        viewport={{ once, margin: `-${offset}px` }}
        transition={{ duration: 0.5, delay, ease: EASE }}
        className={cn("will-change-transform", className)}
        style={style}
      >
        {children}
      </motion.div>
    );
  },
);
Reveal.displayName = "Reveal";

/**
 * StaggerGroup — wraps a list of StaggerItems and sequences their entrance.
 */
interface StaggerGroupProps {
  children: ReactNode;
  stagger?: number;
  delay?: number;
  className?: string;
}

export const StaggerGroup = forwardRef<HTMLDivElement, StaggerGroupProps>(
  ({ children, stagger = 0.08, delay = 0, className }, ref) => {
    const prefersReduced = useReducedMotion();

    if (prefersReduced) {
      return (
        <div ref={ref} className={className}>
          {children}
        </div>
      );
    }

    return (
      <motion.div
        ref={ref}
        initial="hidden"
        whileInView="visible"
        viewport={{ once: true, margin: "-60px" }}
        variants={{
          hidden: {},
          visible: { transition: { staggerChildren: stagger, delayChildren: delay } },
        }}
        className={className}
      >
        {children}
      </motion.div>
    );
  },
);
StaggerGroup.displayName = "StaggerGroup";

/**
 * StaggerItem — individual item inside a StaggerGroup.
 */
interface StaggerItemProps {
  children: ReactNode;
  distance?: number;
  className?: string;
}

export const StaggerItem = forwardRef<HTMLDivElement, StaggerItemProps>(
  ({ children, distance = 16, className }, ref) => {
    const prefersReduced = useReducedMotion();

    if (prefersReduced) {
      return <div ref={ref} className={className}>{children}</div>;
    }

    return (
      <motion.div
        ref={ref}
        variants={{
          hidden: { opacity: 0, y: distance },
          visible: { opacity: 1, y: 0, transition: { duration: 0.45, ease: EASE } },
        }}
        className={cn("will-change-transform", className)}
      >
        {children}
      </motion.div>
    );
  },
);
StaggerItem.displayName = "StaggerItem";

/**
 * FadeIn — simple mount fade for page sections.
 */
interface FadeInProps {
  children: ReactNode;
  delay?: number;
  duration?: number;
  className?: string;
}

export const FadeIn = forwardRef<HTMLDivElement, FadeInProps>(
  ({ children, delay = 0, duration = 0.35, className }, ref) => {
    const prefersReduced = useReducedMotion();

    if (prefersReduced) {
      return <div ref={ref} className={className}>{children}</div>;
    }

    return (
      <motion.div
        ref={ref}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration, delay, ease: "easeOut" }}
        className={className}
      >
        {children}
      </motion.div>
    );
  },
);
FadeIn.displayName = "FadeIn";

/**
 * HoverLift — subtle scale/translate on hover for cards, buttons.
 */
interface HoverLiftProps {
  children: ReactNode;
  scale?: number;
  y?: number;
  className?: string;
}

export const HoverLift = forwardRef<HTMLDivElement, HoverLiftProps>(
  ({ children, scale = 1.02, y = -2, className }, ref) => {
    const prefersReduced = useReducedMotion();

    if (prefersReduced) {
      return <div ref={ref} className={className}>{children}</div>;
    }

    return (
      <motion.div
        ref={ref}
        whileHover={{ scale, y, transition: { duration: 0.2, ease: "easeOut" } }}
        className={cn("will-change-transform", className)}
      >
        {children}
      </motion.div>
    );
  },
);
HoverLift.displayName = "HoverLift";