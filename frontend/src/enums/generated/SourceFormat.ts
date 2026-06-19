// AUTO-GENERATED from OpenAPI definitions

export enum SourceFormat {
  FIT = 'fit',
  GPX = 'gpx',
  TCX = 'tcx',
  KMZ = 'kmz',
  KML = 'kml',
}

export const SourceFormatList = [
  SourceFormat.FIT,
  SourceFormat.GPX,
  SourceFormat.TCX,
  SourceFormat.KMZ,
  SourceFormat.KML,
] as const;

export type SourceFormatType = typeof SourceFormatList[number];

export const SourceFormatLabels: Record<SourceFormatType, string> = {
  [SourceFormat.FIT]: 'Fit',
  [SourceFormat.GPX]: 'Gpx',
  [SourceFormat.TCX]: 'Tcx',
  [SourceFormat.KMZ]: 'Kmz',
  [SourceFormat.KML]: 'Kml',
};
