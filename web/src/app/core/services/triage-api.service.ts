import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  BugReportRequest,
  BugReportAccepted,
  TicketListResponse,
} from '../models/bug-report.model';

/**
 * REST client for the Fara-Hack triage backend.
 * Same-origin paths — Nginx proxies /api → backend container.
 */
@Injectable({ providedIn: 'root' })
export class TriageApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiUrl;

  health(): Observable<{ status: string }> {
    return this.http.get<{ status: string }>(`${this.base}/health`);
  }

  submitReport(report: BugReportRequest): Observable<BugReportAccepted> {
    return this.http.post<BugReportAccepted>(`${this.base}/triage/report`, report);
  }

  listTickets(): Observable<TicketListResponse> {
    return this.http.get<TicketListResponse>(`${this.base}/triage/tickets`);
  }

  resolveTicket(ticketId: string): Observable<{ status: string; ticketId: string }> {
    return this.http.post<{ status: string; ticketId: string }>(
      `${this.base}/triage/tickets/${ticketId}/resolve`,
      {},
    );
  }
}
