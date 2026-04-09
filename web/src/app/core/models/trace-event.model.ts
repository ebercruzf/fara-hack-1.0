/**
 * Reasoning trace envelope emitted by the backend over WS /ws/events.
 * Mirrors the JSON shape produced by BugReportController#publishTrace.
 */
export interface TraceEvent {
  type: 'TRACE' | 'CONNECTED' | 'ERROR' | 'DONE';
  correlationId: string;
  step?: string;
  actor?: string;
  thought?: string;
  timestamp: string;
}

export type PipelineStep =
  | 'STEP_1_RECEIVED'
  | 'STEP_2_TRIAGE'
  | 'STEP_2_5_FORENSIC'
  | 'STEP_3_TICKET'
  | 'STEP_4_NOTIFY'
  | 'STEP_5_REPORTER_NOTIFIED'
  | 'STEP_6_MITIGATION'
  | 'DONE'
  | 'ERROR';
