// AUTO-GENERATED from OpenAPI definitions

export enum ActivityType {
  CYCLING = 'cycling',
  RUNNING = 'running',
  WALKING = 'walking',
  HIKING = 'hiking',
  SWIMMING = 'swimming',
  GENERIC = 'generic',
  OTHER = 'other',
}

export const ActivityTypeList = [
  ActivityType.CYCLING,
  ActivityType.RUNNING,
  ActivityType.WALKING,
  ActivityType.HIKING,
  ActivityType.SWIMMING,
  ActivityType.GENERIC,
  ActivityType.OTHER,
] as const;

export type ActivityTypeType = typeof ActivityTypeList[number];

export const ActivityTypeLabels: Record<ActivityTypeType, string> = {
  [ActivityType.CYCLING]: 'Cycling',
  [ActivityType.RUNNING]: 'Running',
  [ActivityType.WALKING]: 'Walking',
  [ActivityType.HIKING]: 'Hiking',
  [ActivityType.SWIMMING]: 'Swimming',
  [ActivityType.GENERIC]: 'Generic',
  [ActivityType.OTHER]: 'Other',
};
