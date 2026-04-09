import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  signal,
  OnInit,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { TriageApiService } from '../../core/services/triage-api.service';
import { TriageWsService } from '../../core/services/triage-ws.service';
import { TraceEvent } from '../../core/models/trace-event.model';
import { TicketDto } from '../../core/models/bug-report.model';

/**
 * IncidentIntakeComponent — single-component UI for the AgentX submission.
 *
 * <p>Implements:</p>
 * <ul>
 *   <li><b>Step 1 (Ingest)</b> — multimodal form: title, description,
 *       stack trace, optional attachment (file input wired but
 *       attachment is not POSTed in v1.0.0; see TODO in
 *       {@link onSubmit})</li>
 *   <li><b>Reasoning Trace panel</b> — terminal-styled list of
 *       TraceEvent received over WS, scrolling in real time</li>
 *   <li><b>Ticket panel</b> — auto-refreshed list of created tickets
 *       with state badge and "Mark Resolved" button (triggers Step 5)</li>
 * </ul>
 *
 * Reactivity uses Angular Signals throughout — no manual subscription
 * management. The WS service is rxjs-based; bridging happens via
 * {@link takeUntilDestroyed}.
 */
@Component({
  selector: 'app-incident-intake',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './incident-intake.component.html',
  styleUrl: './incident-intake.component.scss',
})
export class IncidentIntakeComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(TriageApiService);
  private readonly ws = inject(TriageWsService);
  private readonly destroyRef = inject(DestroyRef);

  /** Reactive form for the bug report. */
  readonly form = this.fb.nonNullable.group({
    reporterEmail: ['', [Validators.required, Validators.email]],
    title: ['', [Validators.required, Validators.maxLength(200)]],
    description: ['', [Validators.required, Validators.maxLength(8000)]],
    stackTrace: ['', [Validators.maxLength(16000)]],
  });

  // ─── reactive state (Angular Signals) ───────────────────────────────
  readonly submitting = signal(false);
  readonly currentCorrelationId = signal<string>('');
  readonly traces = signal<TraceEvent[]>([]);
  readonly tickets = signal<TicketDto[]>([]);
  readonly connected = signal(false);
  readonly attachedFile = signal<File | null>(null);
  readonly attachmentBase64 = signal<string>('');
  readonly attachmentMime = signal<string>('');
  readonly errorMessage = signal<string>('');
  readonly pipelineComplete = signal(false);

  ngOnInit(): void {
    this.ws.events$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((event) => {
        this.traces.update((list) => [...list, event]);
        if (event.type === 'DONE') {
          this.pipelineComplete.set(true);
          this.refreshTickets();
        }
      });

    this.ws.isConnected$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((c) => this.connected.set(c));

    this.refreshTickets();
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.attachedFile.set(file);
    this.attachmentBase64.set('');
    this.attachmentMime.set('');

    if (!file) return;

    // Hard cap so the JSON body doesn't blow up — 5 MB raw max.
    if (file.size > 5_000_000) {
      this.errorMessage.set('attachment too large (max 5 MB)');
      this.attachedFile.set(null);
      input.value = '';
      return;
    }

    // Multimodal path:
    // - Images (PNG/JPG/etc.) → encode as base64 and ship as the
    //   second modality to the multimodal LLM.
    // - Text files (logs, stack traces) → also encode as base64 so
    //   the backend can pass them to the LLM as a separate part if
    //   the model supports it; otherwise the backend's prompt
    //   builder will inline the text.
    this.readFileAsBase64(file).then((b64) => {
      if (b64) {
        this.attachmentBase64.set(b64);
        this.attachmentMime.set(file.type || 'application/octet-stream');
        this.errorMessage.set('');
      }
    }).catch((err) => {
      this.errorMessage.set('failed to read attachment: ' + err);
      this.attachedFile.set(null);
    });
  }

  onSubmit(): void {
    if (this.form.invalid || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.errorMessage.set('');
    this.traces.set([]);
    this.pipelineComplete.set(false);

    const formValue = this.form.getRawValue();
    const file = this.attachedFile();

    const payload = {
      ...formValue,
      attachmentBase64: this.attachmentBase64() || undefined,
      attachmentMime: this.attachmentMime() || undefined,
      attachmentName: file?.name ?? undefined,
    };

    this.api.submitReport(payload).subscribe({
      next: (accepted) => {
        this.currentCorrelationId.set(accepted.correlationId);
        this.ws.connect(accepted.correlationId);
        this.submitting.set(false);
      },
      error: (err) => {
        const msg = err?.error?.error ?? err?.message ?? 'submission failed';
        this.errorMessage.set(msg);
        this.submitting.set(false);
      },
    });
  }

  refreshTickets(): void {
    this.api.listTickets().subscribe({
      next: (resp) => this.tickets.set(resp.tickets),
      error: () => { /* silent — non-critical */ },
    });
  }

  resolveTicket(ticketId: string): void {
    this.api.resolveTicket(ticketId).subscribe({
      next: () => this.refreshTickets(),
      error: (err) => this.errorMessage.set(err?.error?.error ?? 'resolve failed'),
    });
  }

  trackByIndex(index: number): number {
    return index;
  }

  trackByTicketId(_: number, t: TicketDto): string {
    return t.ticketId;
  }

  /** Visual badge color per pipeline step. */
  stepClass(step: string | undefined): string {
    if (!step) return 'step-default';
    if (step.startsWith('STEP_1')) return 'step-1';
    if (step.startsWith('STEP_2_5')) return 'step-2-5';
    if (step.startsWith('STEP_2')) return 'step-2';
    if (step.startsWith('STEP_3')) return 'step-3';
    if (step.startsWith('STEP_4')) return 'step-4';
    if (step.startsWith('STEP_5')) return 'step-5';
    if (step.startsWith('STEP_6')) return 'step-6';
    if (step === 'DONE') return 'step-done';
    if (step === 'ERROR') return 'step-error';
    return 'step-default';
  }

  severityClass(severity: string): string {
    return `sev-${severity.toLowerCase()}`;
  }

  /**
   * Reads a file and returns its base64 encoding (without the
   * `data:...;base64,` prefix — the backend prepends the prefix
   * when building the OpenAI vision payload).
   */
  private readFileAsBase64(file: File): Promise<string | null> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        const result = reader.result as string;
        // FileReader.readAsDataURL returns "data:<mime>;base64,<...>"
        // We strip the prefix and return just the base64 payload.
        const comma = result.indexOf(',');
        resolve(comma >= 0 ? result.substring(comma + 1) : result);
      };
      reader.onerror = () => reject(reader.error?.message ?? 'unknown read error');
      reader.readAsDataURL(file);
    });
  }
}
