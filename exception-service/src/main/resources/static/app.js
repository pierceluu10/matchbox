// Ops dashboard for reconciliation exceptions, served by exception-service on the same origin.
const REFRESH_MS = 10_000;
const PAGE_SIZE = 200;
const PAYMENT_ID_PREFIX = 8;

const PROBLEMS = {
  AMOUNT_MISMATCH: { label: 'Amount mismatch', plural: 'amount mismatches' },
  MISSING_SETTLEMENT: { label: 'Missing settlement', plural: 'missing settlements' },
  UNEXPECTED_SETTLEMENT: { label: 'Unexpected settlement', plural: 'unexpected settlements' },
};

const state = { status: 'OPEN', type: '', selected: null };

const elements = {
  summary: document.getElementById('summary'),
  rows: document.getElementById('rows'),
  empty: document.getElementById('empty'),
  notice: document.getElementById('notice'),
  freshness: document.getElementById('freshness'),
  drawer: document.getElementById('drawer'),
  drawerTitle: document.getElementById('drawer-title'),
  drawerFacts: document.getElementById('drawer-facts'),
  drawerAction: document.getElementById('drawer-action'),
  drawerResolution: document.getElementById('drawer-resolution'),
  resolveForm: document.getElementById('resolve-form'),
  resolveNote: document.getElementById('resolve-note'),
  resolveError: document.getElementById('resolve-error'),
};

const money = new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const signedMoney = new Intl.NumberFormat(undefined, {
  minimumFractionDigits: 2, maximumFractionDigits: 2, signDisplay: 'exceptZero',
});
const timestamp = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const clock = new Intl.DateTimeFormat(undefined, { timeStyle: 'medium' });

class ApiError extends Error {
  constructor(status, detail) {
    super(detail || `Request failed with status ${status}`);
    this.status = status;
  }
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...options.headers },
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new ApiError(response.status, problem.detail);
  }
  return response.json();
}

function formatAmount(value) {
  return value == null ? '—' : money.format(value);
}

// Bank settled minus what we charged; null when one side never arrived.
function difference(exception) {
  if (exception.expectedAmount == null || exception.settledAmount == null) {
    return null;
  }
  return Math.round((exception.settledAmount - exception.expectedAmount) * 100) / 100;
}

function describeDifference(exception) {
  const delta = difference(exception);
  if (delta === null) {
    const text = exception.settledAmount == null ? 'Not settled' : 'Not charged';
    return { text, direction: 'absent' };
  }
  return { text: signedMoney.format(delta), direction: delta < 0 ? 'short' : 'over' };
}

function renderSummary(summary) {
  const open = Object.keys(PROBLEMS).map((type) => ({
    type,
    count: summary.filter((row) => row.type === type && row.status === 'OPEN')
      .reduce((total, row) => total + row.count, 0),
  }));
  const total = open.reduce((sum, item) => sum + item.count, 0);
  elements.summary.replaceChildren();
  if (total === 0) {
    elements.summary.textContent = 'Every settlement matches what we charged.';
    return;
  }
  elements.summary.append(`${total.toLocaleString()} open: `);
  open.forEach((item, index) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = `${item.count.toLocaleString()} ${PROBLEMS[item.type].plural}`;
    button.setAttribute('aria-pressed', String(state.type === item.type));
    button.addEventListener('click', () => {
      state.type = state.type === item.type ? '' : item.type;
      refresh();
    });
    elements.summary.append(button);
    elements.summary.append(index === open.length - 1 ? '.' : index === open.length - 2 ? ' and ' : ', ');
  });
}

function cell(text, className, label) {
  const td = document.createElement('td');
  td.textContent = text;
  if (className) td.className = className;
  if (label) td.dataset.label = label;
  return td;
}

function renderRows(exceptions) {
  elements.rows.replaceChildren(...exceptions.map((exception) => {
    const row = document.createElement('tr');
    row.tabIndex = 0;
    row.addEventListener('click', () => openDrawer(exception));
    row.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        openDrawer(exception);
      }
    });

    const problem = document.createElement('span');
    problem.className = 'problem';
    problem.dataset.type = exception.type;
    problem.dataset.status = exception.status;
    problem.textContent = exception.status === 'RESOLVED'
      ? `${PROBLEMS[exception.type].label} (resolved)` : PROBLEMS[exception.type].label;
    const problemCell = document.createElement('td');
    problemCell.append(problem);

    const paymentCell = cell(exception.paymentId.slice(0, PAYMENT_ID_PREFIX), 'payment');
    paymentCell.title = exception.paymentId;

    const delta = describeDifference(exception);
    const deltaCell = cell('', 'money', 'Difference');
    const deltaValue = document.createElement('span');
    deltaValue.className = 'difference';
    deltaValue.dataset.direction = delta.direction;
    deltaValue.textContent = delta.text;
    deltaCell.append(deltaValue);

    row.append(
      cell(timestamp.format(new Date(exception.detectedAt))),
      paymentCell,
      problemCell,
      cell(formatAmount(exception.expectedAmount), 'money', 'We charged'),
      cell(formatAmount(exception.settledAmount), 'money', 'Bank settled'),
      deltaCell,
      cell(exception.status === 'OPEN' ? 'Review' : 'View', 'row-action'),
    );
    return row;
  }));
  elements.empty.hidden = exceptions.length > 0;
  if (exceptions.length === 0) {
    elements.empty.textContent = state.status === 'OPEN'
      ? 'No open exceptions. Everything the bank settled matches what we charged.'
      : 'No exceptions match this filter.';
  }
}

function fact(term, value) {
  const dt = document.createElement('dt');
  dt.textContent = term;
  const dd = document.createElement('dd');
  dd.textContent = value;
  return [dt, dd];
}

function openDrawer(exception) {
  state.selected = exception;
  elements.drawerTitle.textContent = PROBLEMS[exception.type].label;
  elements.drawerFacts.replaceChildren(
    ...fact('Payment', exception.paymentId),
    ...fact('We charged', formatAmount(exception.expectedAmount)),
    ...fact('Bank settled', formatAmount(exception.settledAmount)),
    ...fact('Difference', describeDifference(exception).text),
    ...fact('Detected', timestamp.format(new Date(exception.detectedAt))),
  );
  elements.drawerAction.textContent = exception.recommendedAction;
  const resolved = exception.status === 'RESOLVED';
  elements.resolveForm.hidden = resolved;
  elements.drawerResolution.hidden = !resolved;
  if (resolved) {
    elements.drawerResolution.textContent =
      `Resolved ${timestamp.format(new Date(exception.resolvedAt))}: ${exception.resolutionNote}`;
  }
  elements.resolveNote.value = '';
  elements.resolveError.hidden = true;
  elements.drawer.showModal();
}

async function resolveSelected(event) {
  event.preventDefault();
  const note = elements.resolveNote.value.trim();
  if (!note) {
    elements.resolveError.textContent = 'Describe how the exception was resolved.';
    elements.resolveError.hidden = false;
    return;
  }
  const submit = elements.resolveForm.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    await request(`/exceptions/${state.selected.exceptionId}/resolve`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    });
    elements.drawer.close();
    await refresh();
  } catch (error) {
    if (!(error instanceof ApiError || error instanceof TypeError)) throw error;
    elements.resolveError.textContent = error.status === 409
      ? 'Someone already resolved this exception.' : `Couldn't resolve: ${error.message}`;
    elements.resolveError.hidden = false;
  } finally {
    submit.disabled = false;
  }
}

async function refresh() {
  const query = new URLSearchParams({ limit: String(PAGE_SIZE) });
  if (state.status) query.set('status', state.status);
  if (state.type) query.set('type', state.type);
  try {
    const [summary, exceptions] = await Promise.all([
      request('/exceptions/summary'),
      request(`/exceptions?${query}`),
    ]);
    renderSummary(summary);
    renderRows(exceptions);
    elements.notice.hidden = true;
    elements.freshness.textContent = `Updated ${clock.format(new Date())}`;
  } catch (error) {
    // TypeError is what fetch throws when the service is unreachable.
    if (!(error instanceof ApiError || error instanceof TypeError)) throw error;
    elements.notice.textContent = `Couldn't load exceptions (${error.message}). Retrying in ${REFRESH_MS / 1000} seconds.`;
    elements.notice.hidden = false;
  }
}

document.querySelectorAll('.status-filter button').forEach((button) => {
  button.addEventListener('click', () => {
    state.status = button.dataset.status;
    document.querySelectorAll('.status-filter button')
      .forEach((other) => other.setAttribute('aria-checked', String(other === button)));
    refresh();
  });
});
elements.resolveForm.addEventListener('submit', resolveSelected);

refresh();
setInterval(() => {
  if (!elements.drawer.open) refresh();
}, REFRESH_MS);
