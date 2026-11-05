import { useEffect, useMemo, useRef, useState } from "react";
import { AgGridReact } from "ag-grid-react";
import {
  CellStyleModule,
  ClientSideRowModelModule,
  ExternalFilterModule,
  ModuleRegistry,
  PinnedRowModule,
  QuickFilterModule,
  RenderApiModule,
  RowApiModule,
  RowStyleModule,
  themeQuartz,
  type CellKeyDownEvent,
  type ColDef,
  type GridApi,
  type ICellRendererParams,
  type IRowNode,
  type RowClassRules,
} from "ag-grid-community";
import type { Agent, LeashCard, LedgerEntry, Outcome } from "../../types";
import { captureEntry, refundEntry } from "../../api";
import { money } from "../../lib/format";
import { dateTimeUk, STATUS_LABEL } from "../../lib/status";
import { agentFromHash, ledgerTotals, matchesSearch, riskLevel, searchText } from "../../lib/ledgerView";
import { OutcomePill } from "../ui/OutcomePill";
import { ReasonDrawer } from "../ledger/ReasonDrawer";

// Only the modules the ledger uses (https://www.ag-grid.com/react-data-grid/modules/), not AllCommunityModule.
ModuleRegistry.registerModules([ClientSideRowModelModule, QuickFilterModule, ExternalFilterModule, PinnedRowModule,
  RowStyleModule, CellStyleModule, RowApiModule, RenderApiModule]);

/**
 * Quartz with every colour pointed at Leash's tokens, so the grid is burgundy Zenith in both themes and switches with
 * the rest of the page (parameters accept var() expressions: https://www.ag-grid.com/react-data-grid/theming-parameters/).
 */
const leashTheme = themeQuartz.withParams({
  backgroundColor: "var(--panel)",
  foregroundColor: "var(--text)",
  textColor: "var(--text)",
  subtleTextColor: "var(--text-3)",
  accentColor: "var(--accent)",
  borderColor: "var(--line)",
  chromeBackgroundColor: "var(--panel-2)",
  headerBackgroundColor: "var(--panel-2)",
  headerTextColor: "var(--text-3)",
  headerFontWeight: 600,
  headerFontSize: 12,
  rowHoverColor: "var(--accent-soft)",
  selectedRowBackgroundColor: "var(--accent-soft)",
  oddRowBackgroundColor: "var(--panel)",
  fontFamily: ["Inter Variable", "Inter", "ui-sans-serif", "system-ui", "sans-serif"],
  fontSize: 13.5,
  dataFontSize: 13.5,
  rowHeight: 54,
  headerHeight: 40,
  wrapperBorder: false,
  wrapperBorderRadius: 0,
  columnBorder: false,
  headerColumnBorder: false,
  pinnedRowBorder: { width: 1, color: "var(--line-2)" },
  pinnedRowBackgroundColor: "var(--panel-2)",
});

type Row = LedgerEntry & { __total?: ReturnType<typeof ledgerTotals> };
const OUTCOMES: (Outcome | "ALL")[] = ["ALL", "APPROVE", "TUG", "BLOCK"];
const OUTCOME_WORD = { ALL: "All", APPROVE: "Approved", TUG: "Tugs", BLOCK: "Blocked" } as const;
const idOf = (e: LedgerEntry) => e.id ?? e.request.id;

function useNarrow(): boolean {
  const q = "(max-width: 640px)";
  const [narrow, setNarrow] = useState(() => typeof window !== "undefined" && window.matchMedia(q).matches);
  useEffect(() => {
    const m = window.matchMedia(q);
    const on = () => setNarrow(m.matches);
    m.addEventListener("change", on);
    return () => m.removeEventListener("change", on);
  }, []);
  return narrow;
}

/** Every request, paid or refused: AG Grid with live rows, search, filters and a pinned total. */
export function LedgerGrid({ ledger, leashes, latestId, onChanged }: {
  ledger: LedgerEntry[];
  leashes: LeashCard[];
  latestId: string | null;
  onChanged: () => void;
}) {
  const narrow = useNarrow();
  const api = useRef<GridApi<Row> | null>(null);
  const [search, setSearch] = useState("");
  const [outcome, setOutcome] = useState<Outcome | "ALL">("ALL");
  const [agentFilter, setAgentFilter] = useState<string>(() => agentFromHash(window.location.hash) ?? "ALL");
  const [openId, setOpenId] = useState<string | null>(null);
  const agents = useMemo(() => new Map<string, Agent>(leashes.map((l) => [l.agent.id, l.agent])), [leashes]);

  // External filters must be readable from AG Grid's callbacks without re-creating them.
  const filters = useRef({ outcome, agentFilter });
  filters.current = { outcome, agentFilter };
  useEffect(() => { api.current?.onFilterChanged(); }, [outcome, agentFilter]);

  // Totals follow what's shown (search + filters), computed from the same pure rules the grid filters with.
  const totals = useMemo(() => ledgerTotals(ledger.filter((e) =>
    (outcome === "ALL" || e.decision.outcome === outcome)
    && (agentFilter === "ALL" || e.request.agentId === agentFilter)
    && matchesSearch(e, search, agents.get(e.request.agentId)?.name))), [ledger, outcome, agentFilter, search, agents]);

  const firstCol = narrow ? "item" : "time";
  const columns = useMemo<ColDef<Row>[]>(() => {
    const spanTotal = (id: string): ColDef<Row>["colSpan"] => (p) => (p.node?.rowPinned && id === firstCol ? 99 : 1);
    return [
      {
        colId: "time", headerName: "When", hide: narrow, width: 132, sortable: true, sort: "desc",
        valueGetter: (p) => p.data?.request.requestedAt, colSpan: spanTotal("time"),
        cellRenderer: (p: ICellRendererParams<Row>) => p.node.rowPinned
          ? <TotalCell row={p.data} /> : <span className="num dim">{dateTimeUk(p.value)}</span>,
      },
      {
        colId: "agent", headerName: "Agent", hide: narrow, width: 140,
        valueGetter: (p) => (p.data ? agents.get(p.data.request.agentId)?.name ?? p.data.request.agentId : ""),
        cellRenderer: (p: ICellRendererParams<Row>) => {
          const a = p.data ? agents.get(p.data.request.agentId) : undefined;
          return <span className="ag-agent"><i className={`id-mark id-${a?.kind ?? "other"}`} />{p.value}</span>;
        },
      },
      {
        colId: "item", headerName: "Item", flex: 1, minWidth: narrow ? 120 : 200, colSpan: spanTotal("item"),
        valueGetter: (p) => p.data?.request.description,
        // Search finds reasons and the AI's sentence too, not just the visible columns (same text the totals use).
        getQuickFilterText: (p) => (p.data ? searchText(p.data, agents.get(p.data.request.agentId)?.name) : ""),
        cellRenderer: (p: ICellRendererParams<Row>) => {
          if (p.node.rowPinned) return <TotalCell row={p.data} />;
          const a = p.data ? agents.get(p.data.request.agentId) : undefined;
          return (
            <span className="ag-item">
              <span className="ag-item-main">{p.value}</span>
              {narrow && p.data && (
                <span className="xs dim"><i className={`id-mark id-${a?.kind ?? "other"}`} />{a?.name ?? p.data.request.agentId} · {p.data.request.merchant}</span>
              )}
            </span>
          );
        },
      },
      { colId: "shop", headerName: "Shop", hide: narrow, width: 130, valueGetter: (p) => p.data?.request.merchant },
      {
        colId: "amount", headerName: "Amount", width: narrow ? 86 : 112, sortable: true, type: "rightAligned",
        valueGetter: (p) => p.data?.request.amountMinor,
        cellRenderer: (p: ICellRendererParams<Row>) =>
          p.data ? <span className="num ag-amount">{money(p.data.request.amountMinor, p.data.request.currency)}</span> : null,
      },
      {
        colId: "outcome", headerName: "Decision", width: narrow ? 118 : 124,
        valueGetter: (p) => p.data?.decision.outcome,
        cellRenderer: (p: ICellRendererParams<Row>) => (p.data ? <OutcomePill outcome={p.data.decision.outcome} /> : null),
      },
      {
        colId: "risk", headerName: "Risk", hide: narrow, width: 116,
        valueGetter: (p) => (p.data ? riskLevel(p.data).level : null),
        cellRenderer: (p: ICellRendererParams<Row>) => (p.data ? <RiskTag entry={p.data} /> : null),
      },
      {
        colId: "paypal", headerName: "PayPal order", hide: narrow, width: 190,
        valueGetter: (p) => p.data?.paypalOrderId ?? "",
        cellRenderer: (p: ICellRendererParams<Row>) => {
          if (!p.data || p.node.rowPinned) return null;
          const status = p.data.status ? STATUS_LABEL[p.data.status] : null;
          return (
            <span className="ag-item">
              {p.value ? <span className="num pp ag-item-main">{p.value}</span> : <span className="dim">No order</span>}
              {status && <span className="xs dim">{status}</span>}
            </span>
          );
        },
      },
    ];
  }, [agents, narrow, firstCol]);

  // A row that just arrived over SSE: redraw it so rowClassRules (the brief burgundy highlight) is applied even when
  // AG Grid created the row before the new rule reached it.
  useEffect(() => {
    if (!latestId || !api.current) return;
    const node = api.current.getRowNode(latestId);
    if (node) api.current.redrawRows({ rowNodes: [node] });
  }, [latestId, ledger]);

  const rowClassRules = useMemo<RowClassRules<Row>>(() => ({ "is-new": (p) => !!p.data && !p.node.rowPinned && idOf(p.data) === latestId }), [latestId]);
  const pinned = useMemo<Row[]>(() => (ledger.length ? [{ ...ledger[0], __total: totals }] : []), [ledger, totals]);

  const open = openId ? ledger.find((e) => idOf(e) === openId) ?? null : null;
  const onKey = (e: CellKeyDownEvent<Row>) => {
    const k = (e.event as KeyboardEvent | undefined)?.key;
    if ((k === "Enter" || k === " ") && e.data && !e.node.rowPinned) setOpenId(idOf(e.data));
  };

  if (ledger.length === 0) {
    return <p className="empty">The ledger is empty. Press <b>Run demo</b> to send Larder bot shopping.</p>;
  }
  return (
    <div className="card ledger-card">
      <div className="ledger-tools">
        <label className="ledger-search">
          <span className="sr-only">Search the ledger</span>
          <input type="search" placeholder="Search items, shops, reasons…" value={search} onChange={(e) => setSearch(e.target.value)} />
        </label>
        <div className="seg" role="group" aria-label="Filter by decision">
          {OUTCOMES.map((o) => (
            <button key={o} type="button" className={`seg-btn ${o === "ALL" ? "" : `out-${o}`} ${outcome === o ? "is-on" : ""}`}
                    aria-pressed={outcome === o} onClick={() => setOutcome(o)}>{OUTCOME_WORD[o]}</button>
          ))}
        </div>
        <label className="ledger-agent">
          <span className="sr-only">Filter by agent</span>
          <select value={agentFilter} onChange={(e) => setAgentFilter(e.target.value)}>
            <option value="ALL">All agents</option>
            {leashes.map((l) => <option key={l.agent.id} value={l.agent.id}>{l.agent.name}</option>)}
          </select>
        </label>
      </div>
      <div className="ledger-grid">
        <AgGridReact<Row>
          theme={leashTheme}
          rowData={ledger}
          columnDefs={columns}
          defaultColDef={{ sortable: false, resizable: false, suppressMovable: true }}
          // The total row's ID carries its numbers, so a filter change re-renders it (same ID → AG Grid keeps the cell).
          getRowId={(p) => (p.data.__total ? `total:${p.data.__total.count}:${p.data.__total.paid}:${p.data.__total.waiting}:${p.data.__total.blocked}` : idOf(p.data))}
          domLayout="autoHeight"
          quickFilterText={search}
          cacheQuickFilter={false}
          isExternalFilterPresent={() => filters.current.outcome !== "ALL" || filters.current.agentFilter !== "ALL"}
          doesExternalFilterPass={(n: IRowNode<Row>) => {
            const d = n.data;
            if (!d) return true;
            const { outcome: o, agentFilter: a } = filters.current;
            return (o === "ALL" || d.decision.outcome === o) && (a === "ALL" || d.request.agentId === a);
          }}
          pinnedBottomRowData={pinned}
          rowClassRules={rowClassRules}
          onGridReady={(e) => { api.current = e.api; }}
          rowHeight={narrow ? 68 : undefined}
          onRowClicked={(e) => { if (e.data && !e.node.rowPinned) setOpenId(idOf(e.data)); }}
          onCellKeyDown={onKey}
          rowClass="ag-ledger-row"
          suppressCellFocus={false}
          overlayNoRowsTemplate="<span class='dim'>Nothing matches. Clear the search or filters.</span>"
          ensureDomOrder
        />
      </div>
      {open && (
        <ReasonDrawer entry={open} agent={agents.get(open.request.agentId)} onClose={() => setOpenId(null)}>
          <EntryActions entry={open} onChanged={onChanged} />
        </ReasonDrawer>
      )}
    </div>
  );
}

function TotalCell({ row }: { row?: Row }) {
  const t = row?.__total;
  if (!t) return null;
  return (
    <span className="ag-total">
      <span className="label">Total · {t.count} {t.count === 1 ? "request" : "requests"}</span>
      <span><span className="dim">Paid</span> <b className="num tone-pos">{money(t.paid, t.currency)}</b></span>
      <span><span className="dim">Waiting</span> <b className="num tone-warn">{money(t.waiting, t.currency)}</b></span>
      <span><span className="dim">Blocked</span> <b className="num tone-neg">{money(t.blocked, t.currency)}</b></span>
    </span>
  );
}

export function RiskTag({ entry }: { entry: LedgerEntry }) {
  const { level, byAi } = riskLevel(entry);
  if (level === "NONE") return <span className="dim">—</span>;
  const tone = level === "HIGH" ? "BLOCK" : level === "MEDIUM" ? "TUG" : "APPROVE";
  const word = level === "HIGH" ? "High" : level === "MEDIUM" ? "Medium" : "Low";
  return (
    <span className={`status-tag out-${tone}`} title={byAi ? "Set by the AI risk check" : "Set by Leash's rule-based checks"}>
      {word}{byAi && <span className="ai-tag">AI</span>}
    </span>
  );
}

/**
 * What the human can still do with an entry: confirm in PayPal and capture once confirmed, or, once paid, regret it
 * (full refund through PayPal). Regret asks once more first, because it moves money.
 */
export function EntryActions({ entry, onChanged }: { entry: LedgerEntry; onChanged: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const run = (call: (id: string) => Promise<LedgerEntry>) => {
    setBusy(true);
    setError(null);
    call(idOf(entry))
      .then(() => {
        setConfirming(false);
        onChanged();
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  if (entry.status === "AWAITING_PAYER") {
    return (
      <div className="entry-actions">
        {entry.approveUrl && (
          <a className="btn btn-pp" href={entry.approveUrl} target="_blank" rel="noreferrer">Confirm in PayPal sandbox</a>
        )}
        <button type="button" className="btn" disabled={busy} onClick={() => run(captureEntry)}>I've confirmed: capture</button>
        {error && <p className="form-error" role="alert">{error}</p>}
      </div>
    );
  }

  if (entry.status === "PAID" && entry.paypalCaptureId && !entry.paypalRefundId) {
    const amount = money(entry.request.amountMinor, entry.request.currency);
    return (
      <div className="entry-actions">
        {confirming ? (
          <>
            <span className="entry-actions-ask">Refund {amount} in full to your PayPal?</span>
            <button type="button" className="btn" disabled={busy} onClick={() => run(refundEntry)}>
              {busy ? "Refunding…" : `Yes, refund ${amount}`}
            </button>
            <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => setConfirming(false)}>Keep it</button>
          </>
        ) : (
          <button type="button" className="btn" onClick={() => setConfirming(true)}>Regret: refund</button>
        )}
        {error && <p className="form-error" role="alert">{error}</p>}
      </div>
    );
  }

  return null;
}
