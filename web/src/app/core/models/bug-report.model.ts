/** Payload sent to POST /api/triage/report */
export interface BugReportRequest {
  reporterEmail: string;
  title: string;
  description: string;
  stackTrace?: string;
  /** Optional non-text modality (image / log file) for the multimodal LLM. */
  attachmentBase64?: string;
  attachmentMime?: string;
  attachmentName?: string;
}

/** Response from POST /api/triage/report (HTTP 202) */
export interface BugReportAccepted {
  status: 'accepted';
  correlationId: string;
  wsUrl: string;
}

/** Ticket DTO from GET /api/triage/tickets */
export interface TicketDto {
  ticketId: string;
  title: string;
  state: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED';
  severity: string;
  assignedTo: string;
  sentinelVerified: boolean;
  createdAt: string;
  ticketUrl: string;
}

export interface TicketListResponse {
  count: number;
  tickets: TicketDto[];
}
