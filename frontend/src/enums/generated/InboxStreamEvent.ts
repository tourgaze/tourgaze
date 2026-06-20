// AUTO-GENERATED from OpenAPI definitions

export enum InboxStreamEvent {
  CONNECTED = 'connected',
  INBOX_CHANGED = 'inbox-changed',
}

export const InboxStreamEventList = [
  InboxStreamEvent.CONNECTED,
  InboxStreamEvent.INBOX_CHANGED,
] as const;

export type InboxStreamEventType = typeof InboxStreamEventList[number];

export const InboxStreamEventLabels: Record<InboxStreamEventType, string> = {
  [InboxStreamEvent.CONNECTED]: 'Connected',
  [InboxStreamEvent.INBOX_CHANGED]: 'Inbox-changed',
};
