import { Children, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react';

import { t } from '../../i18n';

const CONTROLS = new Set(['input', 'select', 'textarea']);

interface Props {
  /** Id of the input; the label points to it, error and hint get `<id>-error` / `<id>-hint`. */
  id: string;
  label: ReactNode;
  /** Marks the label with * (Docs/i18n.md#forms). */
  required?: boolean;
  /** Shown below the input, marks the input invalid. */
  error?: string;
  hint?: ReactNode;
  /** More classes of the field, e.g. va-field-check. */
  className?: string;
  /** After the label text, e.g. the mark of a field filled from a confirmation. */
  labelExtra?: ReactNode;
  /** The input first (input, select or textarea), then e.g. a list of suggestions. */
  children: ReactNode;
}

/**
 * A form field with label, required mark, hint and error, the same in every form. The first input, select or
 * textarea among the children gets id, required, aria-invalid and aria-describedby; the hint follows it.
 */
export default function Field({ id, label, required, error, hint, className, labelExtra, children }: Props) {
  const describedBy = [error ? `${id}-error` : null, hint ? `${id}-hint` : null].filter(Boolean).join(' ') || undefined;
  const hintElement = hint ? <span id={`${id}-hint`} className="va-small va-muted">{hint}</span> : null;
  let wired = false;
  const content = Children.map(children, (child) => {
    if (wired || !isValidElement(child) || typeof child.type !== 'string' || !CONTROLS.has(child.type)) {
      return child;
    }
    wired = true;
    const own = child.props as Record<string, unknown>;
    // a problem the caller shows itself (e.g. below a row of fields) stays wired
    const ownDescribedBy = typeof own['aria-describedby'] === 'string' ? own['aria-describedby'] : null;
    const control = cloneElement(child as ReactElement<Record<string, unknown>>, {
      id,
      required: required || undefined,
      'aria-invalid': error ? true : own['aria-invalid'],
      'aria-describedby': [describedBy, ownDescribedBy].filter(Boolean).join(' ') || undefined,
    });
    // the hint right below the input, before e.g. a list of suggestions
    return hint ? <>{control}{hintElement}</> : control;
  });
  return (
    <div className={['va-field', className, error ? 'va-field-invalid' : null].filter(Boolean).join(' ')}>
      <label htmlFor={id}>
        {label}
        {required && <RequiredMark />}
        {labelExtra}
      </label>
      {content}
      {!wired && hintElement}
      {error && <p id={`${id}-error`} className="va-field-error" role="alert">{error}</p>}
    </div>
  );
}

/** The * after the label of a required field; screen readers get "required" from the input. */
export function RequiredMark() {
  return <span className="va-required" aria-hidden="true" title={t('validation.required')}>*</span>;
}

/** Explains the * once per form. */
export function RequiredNote() {
  return <p className="va-small va-muted va-required-note"><RequiredMark /> {t('validation.requiredNote')}</p>;
}
