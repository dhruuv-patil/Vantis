"use client";

import Link from "next/link";
import { useQuery, useQueryClient, useMutation } from "@tanstack/react-query";
import { Bell, CheckCheck, RefreshCw } from "lucide-react";
import { toast } from "sonner";

import {
  fetchNotifications,
  getErrorMessage,
  markAllNotificationsRead,
  markNotificationRead,
} from "@/lib/api";
import type {
  AppNotification,
  NotificationsResponse,
} from "@/types";
import { formatRelativeTime } from "@/lib/format";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

/**
 * Vantis notification inbox.
 *
 * Notifications are entirely server-backed. The client only renders the current
 * signed-in member's inbox and performs mark-read mutations.
 */
export function NotificationsMenu() {
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: ["notifications"],
    queryFn: () => fetchNotifications(),
    refetchInterval: 30_000,
    staleTime: 10_000,
  });

  function seed(data: NotificationsResponse) {
    queryClient.setQueryData(["notifications"], data);
  }

  const readOne = useMutation({
    mutationFn: (id: string) => markNotificationRead(id),
    onSuccess: seed,
    onError: (err) =>
      toast.error(
        getErrorMessage(err, "Could not mark that notification as read."),
      ),
  });

  const readAll = useMutation({
    mutationFn: markAllNotificationsRead,
    onSuccess: (data) => {
      seed(data);
      toast.success("All notifications marked read.");
    },
    onError: (err) =>
      toast.error(
        getErrorMessage(err, "Could not mark notifications as read."),
      ),
  });

  const notifications = query.data?.notifications ?? [];
  const unread = query.data?.unreadCount ?? 0;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          className="relative rounded-full text-muted-foreground hover:bg-muted hover:text-foreground dark:text-white/55 dark:hover:bg-white/[0.05] dark:hover:text-white/85"
          aria-label={
            unread > 0
              ? `Notifications (${unread} unread)`
              : "Notifications"
          }
        >
          <Bell className="size-4" />

          {unread > 0 && (
            <span
              aria-hidden="true"
              className="absolute right-0.5 top-0.5 flex min-w-4 items-center justify-center rounded-full border border-background bg-foreground px-1 py-0.5 text-[9px] font-semibold leading-none text-background dark:border-[#050505] dark:bg-white dark:text-black"
            >
              {unread > 9 ? "9+" : unread}
            </span>
          )}
        </Button>
      </DropdownMenuTrigger>

      <DropdownMenuContent
        align="end"
        sideOffset={8}
        className="w-[min(92vw,380px)] overflow-hidden rounded-xl border border-border bg-popover p-0 text-popover-foreground shadow-2xl backdrop-blur-xl dark:border-white/[0.08] dark:bg-[#0a0a0c]/[0.98] dark:text-white dark:shadow-black/40"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border px-3.5 py-3 dark:border-white/[0.07]">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <span className="text-sm font-medium text-foreground dark:text-white/85">
                Notifications
              </span>

              {unread > 0 && (
                <span className="rounded-full border border-border bg-muted/60 px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground dark:border-white/[0.07] dark:bg-white/[0.04] dark:text-white/50">
                  {unread} unread
                </span>
              )}
            </div>

            <p className="mt-0.5 text-[11px] text-muted-foreground dark:text-white/45">
              Run failures and workflow actions
            </p>
          </div>

          {unread > 0 && (
            <button
              type="button"
              onClick={() => readAll.mutate()}
              disabled={readAll.isPending}
              className="flex shrink-0 items-center gap-1.5 rounded-md px-2 py-1.5 text-[11px] text-muted-foreground transition hover:bg-muted hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50 dark:text-white/45 dark:hover:bg-white/[0.05] dark:hover:text-white"
            >
              <CheckCheck className="size-3.5" />
              {readAll.isPending ? "Marking..." : "Mark all read"}
            </button>
          )}
        </div>

        {/* Content */}
        {query.isPending ? (
          <NotificationSkeleton />
        ) : query.isError ? (
          <NotificationError
            message={getErrorMessage(
              query.error,
              "Could not load notifications.",
            )}
            onRetry={() => void query.refetch()}
            retrying={query.isFetching}
          />
        ) : notifications.length === 0 ? (
          <NotificationEmpty />
        ) : (
          <ul className="max-h-[min(70vh,460px)] divide-y divide-border/60 overflow-y-auto dark:divide-white/[0.055]">
            {notifications.map((notification) => (
              <NotificationRow
                key={notification.id}
                notification={notification}
                onRead={() => readOne.mutate(notification.id)}
                isReading={
                  readOne.isPending &&
                  readOne.variables === notification.id
                }
              />
            ))}
          </ul>
        )}

        {/* Footer */}
        {notifications.length > 0 && !query.isError && (
          <div className="border-t border-border px-3.5 py-2.5 dark:border-white/[0.06]">
            <Link
              href="/notifications"
              className="flex items-center justify-center rounded-md py-1.5 text-[11px] font-medium text-muted-foreground transition hover:bg-muted hover:text-foreground dark:text-white/50 dark:hover:bg-white/[0.035] dark:hover:text-white"
            >
              Open notification center
            </Link>
          </div>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

const LEVEL_DOT: Record<AppNotification["level"], string> = {
  INFO: "bg-muted-foreground/40 dark:bg-white/25",
  WARN: "bg-amber-500 dark:bg-amber-400",
  ERROR: "bg-red-500 dark:bg-red-400",
};

function NotificationRow({
  notification,
  onRead,
  isReading,
}: {
  notification: AppNotification;
  onRead: () => void;
  isReading: boolean;
}) {
  const unread = notification.readAt === null;

  const body = (
    <span className="min-w-0 flex-1">
      <span
        className={cn(
          "block truncate text-xs",
          unread
            ? "font-medium text-foreground dark:text-white/90"
            : "font-normal text-muted-foreground dark:text-white/60",
        )}
      >
        {notification.title}
      </span>

      {notification.body && (
        <span className="mt-0.5 block line-clamp-2 text-[11px] leading-4 text-muted-foreground dark:text-white/50">
          {notification.body}
        </span>
      )}

      <span className="mt-1.5 flex items-center gap-1.5 text-[11px] text-muted-foreground dark:text-white/45">
        <span>{formatRelativeTime(notification.createdAt)}</span>

        {unread && (
          <>
            <span aria-hidden="true">·</span>
            <span
              className={cn(
                "uppercase tracking-wider",
                notification.level === "ERROR"
                  ? "text-red-600 dark:text-red-300/65"
                  : notification.level === "WARN"
                    ? "text-amber-700 dark:text-amber-200/65"
                    : "text-muted-foreground dark:text-white/45",
              )}
            >
              {notification.level.toLowerCase()}
            </span>
          </>
        )}
      </span>
    </span>
  );

  return (
    <li
      className={cn(
        "group flex items-start gap-3 px-3.5 py-3 transition-colors",
        unread
          ? "bg-muted/40 hover:bg-muted/70 dark:bg-white/[0.015] dark:hover:bg-white/[0.035]"
          : "hover:bg-muted/30 dark:hover:bg-white/[0.025]",
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          "mt-1.5 size-2 shrink-0 rounded-full",
          unread
            ? LEVEL_DOT[notification.level]
            : "bg-transparent",
        )}
      />

      {notification.link ? (
        <Link
          href={notification.link}
          onClick={onRead}
          className="flex min-w-0 flex-1"
        >
          {body}
        </Link>
      ) : (
        body
      )}

      {unread && (
        <button
          type="button"
          onClick={(event) => {
            event.stopPropagation();
            onRead();
          }}
          disabled={isReading}
          className="mt-0.5 shrink-0 rounded-md px-1.5 py-1 text-[11px] text-muted-foreground opacity-0 transition hover:bg-muted hover:text-foreground group-hover:opacity-100 disabled:cursor-not-allowed disabled:opacity-50 sm:opacity-100 dark:text-white/45 dark:hover:bg-white/[0.05] dark:hover:text-white"
        >
          {isReading ? "..." : "Read"}
        </button>
      )}
    </li>
  );
}

function NotificationEmpty() {
  return (
    <div className="flex flex-col items-center justify-center px-5 py-12 text-center">
      <span className="flex size-9 items-center justify-center rounded-lg bg-muted text-muted-foreground dark:bg-white/[0.04] dark:text-white/40">
        <Bell className="size-4" />
      </span>

      <p className="mt-3 text-xs font-medium text-foreground dark:text-white/70">
        You&apos;re all caught up
      </p>

      <p className="mt-1 max-w-[230px] text-[11px] leading-4 text-muted-foreground dark:text-white/45">
        Failed runs, approvals, and important workflow events will appear here.
      </p>
    </div>
  );
}

function NotificationError({
  message,
  onRetry,
  retrying,
}: {
  message: string;
  onRetry: () => void;
  retrying: boolean;
}) {
  return (
    <div className="px-4 py-8 text-center">
      <p className="text-xs font-medium text-red-600 dark:text-red-300/80">
        Could not load notifications
      </p>

      <p className="mt-1 text-[11px] leading-4 text-muted-foreground dark:text-white/45">
        {message}
      </p>

      <button
        type="button"
        onClick={onRetry}
        disabled={retrying}
        className="mt-3 inline-flex items-center gap-1.5 rounded-md border border-border bg-card px-2.5 py-1.5 text-[11px] text-muted-foreground transition hover:bg-muted hover:text-foreground disabled:opacity-50 dark:border-white/[0.07] dark:bg-white/[0.025] dark:text-white/50 dark:hover:bg-white/[0.05] dark:hover:text-white"
      >
        <RefreshCw
          className={cn(
            "size-3",
            retrying && "animate-spin",
          )}
        />
        Retry
      </button>
    </div>
  );
}

function NotificationSkeleton() {
  return (
    <div className="space-y-0">
      {Array.from({ length: 4 }).map((_, index) => (
        <div
          key={index}
          className="flex items-start gap-3 border-b border-border/60 px-3.5 py-3 last:border-0 dark:border-white/[0.055]"
        >
          <span className="mt-1.5 size-2 shrink-0 rounded-full bg-muted dark:bg-white/[0.07]" />

          <span className="flex min-w-0 flex-1 flex-col gap-1.5">
            <span className="h-3 w-40 max-w-full rounded bg-muted dark:bg-white/[0.06]" />
            <span className="h-2.5 w-56 max-w-full rounded bg-muted/70 dark:bg-white/[0.04]" />
            <span className="h-2 w-16 rounded bg-muted/60 dark:bg-white/[0.035]" />
          </span>
        </div>
      ))}
    </div>
  );
}
