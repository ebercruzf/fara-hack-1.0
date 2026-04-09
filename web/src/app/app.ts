import { ChangeDetectionStrategy, Component } from '@angular/core';
import { IncidentIntakeComponent } from './features/intake/incident-intake.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [IncidentIntakeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-incident-intake></app-incident-intake>`,
})
export class App {}
