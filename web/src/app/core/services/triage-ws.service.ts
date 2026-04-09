import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, Subject, filter, map } from 'rxjs';
import { webSocket, WebSocketSubject } from 'rxjs/webSocket';
import { environment } from '../../../environments/environment';
import { TraceEvent } from '../models/trace-event.model';

/**
 * Reactive WebSocket client for the Fara-Hack reasoning trace.
 *
 * Pattern cloned from fararoni-audio-ui/src/app/core/services/audio-ws.service.ts:
 *   - rxjs/webSocket factory
 *   - BehaviorSubject for connection state
 *   - Subject for raw messages
 *   - filtered observables for downstream consumers (Angular Signals can
 *     subscribe via toSignal())
 *
 * One instance per correlationId. The component re-instantiates when
 * a new bug report is submitted.
 */
@Injectable({ providedIn: 'root' })
export class TriageWsService {
  private socket$: WebSocketSubject<TraceEvent> | null = null;
  private readonly _events$ = new Subject<TraceEvent>();
  private readonly _isConnected$ = new BehaviorSubject<boolean>(false);
  private currentCorrelationId = '';

  /** Stream of every TraceEvent that arrives over the wire */
  readonly events$: Observable<TraceEvent> = this._events$.asObservable();

  readonly isConnected$: Observable<boolean> = this._isConnected$.asObservable();

  /** Emits only TRACE-typed envelopes (filters out CONNECTED/ERROR/DONE markers) */
  readonly traces$: Observable<TraceEvent> = this._events$.pipe(
    filter((e) => e.type === 'TRACE'),
  );

  /** Emits the actor name of every step (useful for activity indicators) */
  readonly actors$: Observable<string> = this.traces$.pipe(
    filter((e) => !!e.actor),
    map((e) => e.actor!),
  );

  /** Emits a single envelope when the pipeline reports DONE */
  readonly done$: Observable<TraceEvent> = this._events$.pipe(
    filter((e) => e.type === 'DONE'),
  );

  /** Emits errors from the pipeline */
  readonly error$: Observable<TraceEvent> = this._events$.pipe(
    filter((e) => e.type === 'ERROR'),
  );

  /**
   * Open a WS subscription for a given correlationId. If already
   * connected to a different correlationId, the previous subscription
   * is closed first.
   */
  connect(correlationId: string): void {
    if (this.socket$ && this.currentCorrelationId === correlationId) {
      return;
    }
    this.disconnect();
    this.currentCorrelationId = correlationId;

    const url = `${environment.wsUrl()}?correlationId=${encodeURIComponent(correlationId)}`;

    this.socket$ = webSocket<TraceEvent>({
      url,
      openObserver: { next: () => this._isConnected$.next(true) },
      closeObserver: { next: () => this._isConnected$.next(false) },
    });

    this.socket$.subscribe({
      next: (event) => this._events$.next(event),
      error: () => {
        this._isConnected$.next(false);
        this.socket$ = null;
      },
      complete: () => {
        this._isConnected$.next(false);
        this.socket$ = null;
      },
    });
  }

  disconnect(): void {
    if (this.socket$) {
      this.socket$.complete();
      this.socket$ = null;
    }
    this._isConnected$.next(false);
    this.currentCorrelationId = '';
  }
}
