import { Fragment, useEffect, useState, type FormEvent } from 'react'
import { League } from './League'
import type { BreakMatch, CallUpStatus, DataSource, IntlMatch, NextFixture, PlayerReport, Risk, TeamReport } from './types'

const LAST_TEAM_KEY = 'fpl-break:lastTeamId'

const RISK_LABEL: Record<Risk, string> = {
  red: 'Injury concern',
  amber: 'Keep an eye',
  green: 'All clear',
}

const CALL_UP_LABEL: Record<CallUpStatus, string> = {
  called: 'Yes',
  withdrawn: 'Withdrew',
  not_called: 'No',
  unknown: 'Unknown',
}

const SOURCE_LABEL = { fotmob: 'FotMob', fpl: 'FPL' } as const

const fmtDay = (iso: string) =>
  new Date(iso).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' })

const fmtTime = (iso: string) => new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })

/** "today 19:45", "tomorrow 17:00", or "Sat 3 Oct" further out (your local time). */
function fmtWhen(iso: string) {
  const d = new Date(iso)
  const today = new Date()
  const tomorrow = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1)
  if (d.toDateString() === today.toDateString()) return `today ${fmtTime(iso)}`
  if (d.toDateString() === tomorrow.toDateString()) return `tomorrow ${fmtTime(iso)}`
  return fmtDay(iso)
}

/** Like fmtWhen, but always with the kick-off time. */
const fmtWhenAt = (iso: string) => (fmtWhen(iso).includes(':') ? fmtWhen(iso) : `${fmtDay(iso)} ${fmtTime(iso)}`)

const fmtThousands = (n: number) => `${n > 0 ? '+' : n < 0 ? '−' : ''}${Math.abs(Math.round(n / 1000))}k`

// While a match is live, refresh this often (the backend re-checks live matches every minute).
const LIVE_REFRESH_MS = 90_000

function readLastTeam() {
  try {
    return localStorage.getItem(LAST_TEAM_KEY) ?? ''
  } catch {
    return ''
  }
}

function saveLastTeam(id: string) {
  try {
    localStorage.setItem(LAST_TEAM_KEY, id)
  } catch {
    // Storage unavailable (private mode); remembering the ID is only a convenience.
  }
}

/** A summary chip the list can be filtered by. Each one matches a set of players. */
export type ChipFilter = 'called' | 'withdrawn' | 'not_called' | Risk

const matchesFilter = (p: PlayerReport, f: ChipFilter | null) => {
  if (f === null) return true
  if (f === 'called' || f === 'withdrawn') return p.callUp.status === f
  if (f === 'not_called') return p.callUp.status === 'not_called' || p.callUp.status === 'unknown'
  return p.risk === f
}

const POSITIONS = ['All', 'GKP', 'DEF', 'MID', 'FWD'] as const
type PositionTab = (typeof POSITIONS)[number]
const POSITION_LABEL: Record<PositionTab, string> = { All: 'All', GKP: 'GK', DEF: 'DEF', MID: 'MID', FWD: 'FWD' }

/** Only players still with their squad have fixtures left that matter. */
const isAway = (p: PlayerReport) => p.callUp.status === 'called'
const upcoming = (p: PlayerReport) => p.breakFixtures.filter((f) => !f.finished && !f.live)
// "Out" only means something for a player who was in the squad.
const played = (p: PlayerReport) =>
  p.breakFixtures.filter((f) => f.finished && (isAway(p) || f.involvement !== 'absent'))

export default function App() {
  const [teamId, setTeamId] = useState(readLastTeam)
  const [report, setReport] = useState<TeamReport | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [filter, setFilter] = useState<ChipFilter | null>(null)
  const [position, setPosition] = useState<PositionTab>('All')
  const [open, setOpen] = useState<Set<number>>(new Set())
  const [view, setView] = useState<'squad' | 'league'>('squad')

  /** `quiet` refreshes keep the page as it is (open rows, last good report) — used while matches are live. */
  async function load(id: string, quiet = false) {
    if (!quiet) {
      setLoading(true)
      setError(null)
    }
    try {
      const res = await fetch(`/api/team/${encodeURIComponent(id)}/break`)
      const body = await res.json()
      if (!res.ok) throw new Error(body.error ?? `Request failed (${res.status})`)
      setReport(body)
      if (!quiet) setOpen(new Set())
      saveLastTeam(id)
    } catch (err) {
      if (quiet) return
      setError((err as Error).message)
      setReport(null)
    } finally {
      if (!quiet) setLoading(false)
    }
  }

  const anyLive = report?.players.some((p) => p.live) ?? false
  useEffect(() => {
    if (!anyLive || !report) return
    const timer = setInterval(() => load(String(report.team.id), true), LIVE_REFRESH_MS)
    return () => clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [anyLive, report?.team.id])

  useEffect(() => {
    if (teamId) load(teamId)
    // Only auto-load the remembered team on first render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (teamId.trim()) load(teamId.trim())
  }

  function toggle(id: number) {
    setOpen((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const players =
    report?.players.filter((p) => matchesFilter(p, filter) && (position === 'All' || p.position === position)) ?? []
  const starters = players.filter((p) => p.squadRole === 'starter')
  const bench = players.filter((p) => p.squadRole === 'bench')

  return (
    <div className="page">
      <header className="masthead">
        <p className="eyebrow">International break tracker</p>
        <h1>Did your players come back in one piece?</h1>
        <form className="search" onSubmit={onSubmit}>
          <label htmlFor="team-id" className="sr-only">
            FPL team ID
          </label>
          <input
            id="team-id"
            inputMode="numeric"
            pattern="[0-9]*"
            placeholder="FPL team ID"
            value={teamId}
            onChange={(e) => setTeamId(e.target.value.replace(/\D/g, ''))}
          />
          <button type="submit" disabled={loading || !teamId}>
            {loading ? 'Checking…' : 'Check squad'}
          </button>
        </form>
      </header>

      {error && <p className="error">{error}</p>}
      {loading && !report && <p className="muted">Fetching FPL squad and international matches…</p>}

      {report && (
        <nav className="tabs" aria-label="Views">
          <button className={view === 'squad' ? 'active' : ''} onClick={() => setView('squad')}>
            My squad
          </button>
          <button className={view === 'league' ? 'active' : ''} onClick={() => setView('league')}>
            Mini-league
          </button>
        </nav>
      )}

      {report && view === 'league' && <League teamId={report.team.id} />}

      {report && view === 'squad' && (
        <main>
          <Summary report={report} filter={filter} onFilter={(f) => setFilter((cur) => (cur === f ? null : f))} />

          <nav className="position-tabs" aria-label="Filter by position">
            {POSITIONS.map((pos) => {
              const n = report.players.filter((p) => matchesFilter(p, filter) && (pos === 'All' || p.position === pos)).length
              return (
                <button key={pos} className={position === pos ? 'active' : ''} onClick={() => setPosition(pos)}>
                  {POSITION_LABEL[pos]} <span className="muted">{n}</span>
                </button>
              )
            })}
            {filter && (
              <button className="clear-filter" onClick={() => setFilter(null)}>
                Clear filter ✕
              </button>
            )}
          </nav>

          <div className="squad">
            <div className="row head" aria-hidden>
              <span>Player</span>
              <span>Called up</span>
              <span>Played</span>
              <span>Still to play</span>
              <span>Back for GW{report.window.beforeEvent}</span>
              <span>Status</span>
            </div>
            {[
              ['Starting XI', starters],
              ['Bench', bench],
            ].map(([label, group]) =>
              (group as PlayerReport[]).length === 0 ? null : (
                <Fragment key={label as string}>
                  <div className="group-label">
                    <span>{label as string}</span>
                  </div>
                  {(group as PlayerReport[]).map((p) => (
                    <PlayerRow key={p.fplId} player={p} open={open.has(p.fplId)} onToggle={() => toggle(p.fplId)} />
                  ))}
                </Fragment>
              ),
            )}
            {players.length === 0 && <p className="muted empty">No players match this filter.</p>}
          </div>

          <Sources sources={report.sources} generatedAt={report.generatedAt} />

          <footer className="muted small">
            Squad from GW{report.team.picksFromEvent} picks. Call-ups cross-checked between FotMob and Transfermarkt. Report built{' '}
            {new Date(report.generatedAt).toLocaleString('en-GB')}.
          </footer>
        </main>
      )}
    </div>
  )
}

function Summary({
  report,
  filter,
  onFilter,
}: {
  report: TeamReport
  filter: ChipFilter | null
  onFilter: (f: ChipFilter) => void
}) {
  const ps = report.players
  const called = ps.filter((p) => p.callUp.status === 'called').length
  const withdrew = ps.filter((p) => p.callUp.status === 'withdrawn').length
  const notCalled = ps.filter((p) => p.callUp.status === 'not_called' || p.callUp.status === 'unknown').length
  // Several players can share a national team, so count each fixture once.
  const remaining = [...new Map(ps.filter(isAway).flatMap(upcoming).map((f) => [f.fotmobMatchId, f])).values()]
  const nations = new Set(ps.filter(isAway).map((p) => p.nationalTeam)).size
  const lastMatch = remaining.map((f) => f.date).sort().at(-1)
  const risks = (['red', 'amber', 'green'] as Risk[]).map((r) => [r, ps.filter((p) => p.risk === r).length] as const)
  // Not a count next to the others: these players are a subset of the ones away, so name them instead.
  const tired = ps
    .filter((p) => p.rest.level === 'tight' || p.rest.level === 'watch')
    .sort((a, b) => (a.rest.restDays ?? 99) - (b.rest.restDays ?? 99))
  const live = ps.filter((p) => p.live)

  return (
    <section className="summary">
      <div className="summary-head">
        <h2>{report.team.name}</h2>
        <p className="muted">
          {report.team.manager} · GW{report.window.afterEvent} → GW{report.window.beforeEvent} break,{' '}
          {fmtDay(report.window.from)} – {fmtDay(report.window.to)}
        </p>
        <p className="break-status">
          {lastMatch ? (
            <>
              <span className="live-dot" aria-hidden /> Break in progress — {remaining.length} matches still to come across{' '}
              {nations} national teams, last one {fmtDay(lastMatch)}
            </>
          ) : (
            'Break over — no national-team matches left for your players'
          )}
        </p>
        {live.length > 0 && (
          <p className="live-now">
            <span className="live-badge">Live</span>
            {live.map((p) => p.webName).join(', ')} {live.length === 1 ? 'is' : 'are'} in an international match right now —
            refreshing automatically
          </p>
        )}
      </div>
      {/* Each group covers all 15 players once, so its numbers add up to the squad. */}
      <div className="stat-groups">
        <div className="stats">
          <span className="stats-label">Call-ups</span>
          <Stat n={called} label="With national team" id="called" filter={filter} onFilter={onFilter} />
          <Stat n={withdrew} label="Withdrew" tone={withdrew ? 'amber' : undefined} id="withdrawn" filter={filter} onFilter={onFilter} />
          <Stat n={notCalled} label="Not called up" id="not_called" filter={filter} onFilter={onFilter} />
        </div>
        <div className="stats">
          <span className="stats-label">Fitness</span>
          {risks.map(([risk, n]) => (
            <Stat key={risk} n={n} label={RISK_LABEL[risk]} tone={n ? risk : undefined} id={risk} filter={filter} onFilter={onFilter} />
          ))}
        </div>
      </div>
      <p className="rest-summary small">
        <span className="stats-label">Rest before GW{report.window.beforeEvent}</span>
        {tired.length === 0
          ? 'Everyone away gets 4+ days before their club game.'
          : tired.map((p, i) => (
              <Fragment key={p.fplId}>
                {i > 0 && ', '}
                <span className={`rest-name rest-${p.rest.level}`} title={p.rest.note}>
                  {p.webName} ({p.rest.restDays} days{p.rest.longTrip && ', long trip'}
                  {p.rest.breakMinutes >= 270 && `, ${p.rest.breakMinutes}'`})
                </span>
              </Fragment>
            ))}
      </p>
    </section>
  )
}

function dataAge(iso: string, now: string) {
  const mins = Math.max(0, Math.round((Date.parse(now) - Date.parse(iso)) / 60_000))
  if (mins < 1) return 'Fetched just now'
  if (mins < 60) return `Data up to ${mins} min old`
  return `Data up to ${Math.round(mins / 60)} h old`
}

function Sources({ sources, generatedAt }: { sources: DataSource[]; generatedAt: string }) {
  return (
    <section className="sources" aria-labelledby="sources-title">
      <h2 id="sources-title">Data sources</h2>
      <ul>
        {sources.map((s) => (
          <li key={s.id} className={`source-card ${s.status}`}>
            <div className="source-head">
              <strong>
                {s.url ? (
                  <a href={s.url} target="_blank" rel="noreferrer">
                    {s.name}
                  </a>
                ) : (
                  s.name
                )}
              </strong>
              <span className={`source-status ${s.status}`}>
                {s.status === 'live'
                  ? s.oldestDataAt
                    ? dataAge(s.oldestDataAt, generatedAt)
                    : 'In use'
                  : 'Coming next'}
              </span>
            </div>
            <ul className="used-for">
              {s.usedFor.map((u) => (
                <li key={u}>{u}</li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
      <p className="muted small">
        FPL, FotMob and Transfermarkt data is cached for 10 minutes to 3 hours to stay within their fair use, so a new
        withdrawal can take a few hours to show.
      </p>
    </section>
  )
}

function Stat({
  n,
  label,
  tone,
  id,
  filter,
  onFilter,
}: {
  n: number
  label: string
  tone?: Risk
  id: ChipFilter
  filter: ChipFilter | null
  onFilter: (f: ChipFilter) => void
}) {
  const active = filter === id
  return (
    <button
      className={`stat${tone ? ` tone-${tone}` : ''}${active ? ' active' : ''}`}
      aria-pressed={active}
      disabled={n === 0 && !active}
      onClick={() => onFilter(id)}
      title={active ? 'Show everyone' : `Show only: ${label.toLowerCase()}`}
    >
      <strong>{n}</strong> {label}
    </button>
  )
}

function PlayerRow({ player: p, open, onToggle }: { player: PlayerReport; open: boolean; onToggle: () => void }) {
  const done = played(p)
  const toCome = upcoming(p)
  const minutes = done.reduce((sum, f) => sum + (f.minutes ?? 0), 0)
  const featured = done.filter((f) => f.involvement === 'started' || f.involvement === 'sub').length
  const detailsId = `details-${p.fplId}`

  return (
    <div className={`player risk-${p.risk}${open ? ' open' : ''}`}>
      <button className="row" aria-expanded={open} aria-controls={detailsId} onClick={onToggle}>
        <span className="cell-player">
          <span className="name">
            {p.webName}
            {p.isCaptain && <span className="tag">C</span>}
            {p.isViceCaptain && <span className="tag">VC</span>}
          </span>
          <span className="muted small">
            {p.position} · {p.club}
            {p.nationalTeam && (
              <>
                {' · '}
                {p.nationalTeam}
                {p.nation && !p.nation.capped && ' (uncapped)'}
              </>
            )}
          </span>
        </span>

        <span className="cell-callup">
          <span className={`callup callup-${p.callUp.status}`}>{CALL_UP_LABEL[p.callUp.status]}</span>
          {p.callUp.agreement === 'conflict' && <span className="conflict" title="Sources disagree">⚠</span>}
        </span>

        <span className="cell-played">
          {done.length === 0 && !p.live ? (
            <span className="muted">—</span>
          ) : (
            <>
              <span className="chips">
                {done.map((f) => (
                  <InvolvementChip key={f.fotmobMatchId} f={f} />
                ))}
                {p.live && <LiveChip player={p} />}
              </span>
              {done.length > 0 && (
                <span className="muted small total" title={`${minutes} minutes, featured in ${featured} of ${done.length} matches played`}>
                  {minutes}' · {featured}/{done.length}
                </span>
              )}
            </>
          )}
        </span>

        <span className="cell-next">
          {isAway(p) && toCome.length > 0 ? (
            <>
              <strong>{toCome.length}</strong>
              <span className="muted small">
                next {fmtWhen(toCome[0].date)} {toCome[0].home ? 'v' : '@'} {toCome[0].opponent}
              </span>
            </>
          ) : (
            <span className="muted">—</span>
          )}
        </span>

        <span className="cell-back">
          <NextGw fixtures={p.nextFixtures} />
          {p.rest.level !== 'n/a' && (
            <span className={`rest rest-${p.rest.level}`} title={p.rest.note}>
              {p.rest.restDays}d rest{p.rest.longTrip && ' ✈'}
            </span>
          )}
        </span>

        <span className="cell-status">
          <span className={`dot risk-${p.risk}`} aria-hidden />
          <span className="status-text">{RISK_LABEL[p.risk]}</span>
          <span className="chevron" aria-hidden>
            ›
          </span>
        </span>
      </button>

      {open && <PlayerDetails id={detailsId} player={p} />}
    </div>
  )
}

function InvolvementChip({ f }: { f: BreakMatch }) {
  const vs = `${f.home ? 'v' : '@'} ${f.opponent}, ${fmtDay(f.date)}`
  const [text, title] = {
    started: [`${f.minutes}'`, `Started, ${f.minutes} min ${vs}`],
    sub: [`+${f.minutes}'`, `Came on, ${f.minutes} min ${vs}`],
    bench: ['Bench', `Unused sub ${vs}`],
    absent: ['Not selected', `Called up, but not in the squad for this match ${vs} (e.g. Germany's split squad)`],
    upcoming: ['', ''],
  }[f.involvement]
  return (
    <span className={`chip chip-${f.involvement}`} title={title}>
      {text}
    </span>
  )
}

const ON_PITCH: Record<string, string> = {
  playing: 'on the pitch',
  subbed_off: 'subbed off',
  bench: 'on the bench',
  not_in_squad: 'not in the matchday squad',
  unknown: 'line-ups not out yet',
}

function LiveChip({ player: p }: { player: PlayerReport }) {
  const l = p.live!
  return (
    <span className="chip chip-live" title={`${l.home ? 'v' : '@'} ${l.opponent} ${l.score ?? ''} — ${ON_PITCH[l.onPitch]}`}>
      Live {l.minute}
    </span>
  )
}

function NextGw({ fixtures }: { fixtures: NextFixture[] }) {
  if (fixtures.length === 0) return <span className="muted">No fixture</span>
  const f = fixtures[0]
  return (
    <span className="next-gw">
      {f.home ? 'v' : '@'} {f.opponent}
      <span className={`fdr fdr-${f.difficulty}`} title={`FPL difficulty ${f.difficulty} of 5`}>
        {f.difficulty}
      </span>
      {fixtures.length > 1 && <span className="muted small">+{fixtures.length - 1}</span>}
    </span>
  )
}

function PlayerDetails({ id, player: p }: { id: string; player: PlayerReport }) {
  const toCome = upcoming(p)
  const pr = p.price
  return (
    <div className="details" id={id}>
      <div className="details-col">
        {p.live && (
          <p className="live-detail">
            <span className="live-badge">Live {p.live.minute}</span> {p.live.home ? 'v' : '@'} {p.live.opponent}
            {p.live.score && ` · ${p.live.score}`} — {ON_PITCH[p.live.onPitch]}
          </p>
        )}
        <h4>International matches</h4>
        {p.matches.length > 0 ? (
          <ul className="matches">
            {p.matches.map((m) => (
              <MatchRow key={m.fotmobMatchId} m={m} />
            ))}
          </ul>
        ) : (
          <p className="muted small">Didn't feature in any match during the break.</p>
        )}

        {toCome.length > 0 && (
          <>
            <h4>{isAway(p) ? 'Still to play' : `${p.nationalTeam} still to play (not in the squad)`}</h4>
            <ul className="fixtures">
              {toCome.map((f) => (
                <li key={f.fotmobMatchId}>
                  <span>
                    {f.home ? 'v' : '@'} {f.opponent}
                  </span>
                  <span className="muted small">
                    {fmtWhenAt(f.date)} · {f.competition}
                  </span>
                </li>
              ))}
            </ul>
          </>
        )}

        <h4>Back to club</h4>
        <ul className="fixtures">
          {p.nextFixtures.map((f) => (
            <li key={f.kickoff}>
              <span>
                {f.home ? 'v' : '@'} {f.opponent} <span className={`fdr fdr-${f.difficulty}`}>{f.difficulty}</span>
              </span>
              <span className="muted small">{fmtWhenAt(f.kickoff)}</span>
            </li>
          ))}
        </ul>
        {p.rest.note && <p className={`small rest-note rest-${p.rest.level}`}>{p.rest.note}</p>}
        <p className="muted small">
          £{pr.now.toFixed(1)}m
          {pr.changeThisGw !== 0 && ` (${pr.changeThisGw > 0 ? '+' : ''}${pr.changeThisGw.toFixed(1)} since the deadline)`} · net
          transfers {fmtThousands(pr.netTransfers)}
          {pr.netTransfers <= -50_000 && ' (heavy transfers out)'}
          {pr.netTransfers >= 100_000 && ' (heavy transfers in)'}
        </p>
      </div>

      <div className="details-col">
        <h4>Call-up sources</h4>
        <ul className="checks">
          {p.callUp.checks.map((c) => (
            <li key={c.source}>
              <span className="source">{c.source}</span>
              <span className={`callup callup-${c.status}`}>{CALL_UP_LABEL[c.status]}</span>
              <span className="small">
                {c.detail}
                {c.url && (
                  <>
                    {' '}
                    <a href={c.url} target="_blank" rel="noreferrer">
                      Source
                    </a>
                  </>
                )}
              </span>
            </li>
          ))}
          {p.callUp.checks.length === 0 && <li className="muted small">No source could place this player.</li>}
        </ul>
        {p.callUp.agreement === 'agree' && <p className="agree small">✓ Sources agree</p>}
        {p.callUp.agreement === 'conflict' && (
          <p className="conflict small">⚠ Sources disagree — check the latest squad news before your transfers.</p>
        )}
        {p.callUp.agreement === 'single' && p.callUp.checks.length > 0 && (
          <p className="muted small">Only one source could check this player.</p>
        )}

        {p.signals.length > 0 && (
          <>
            <h4>Injury &amp; availability</h4>
            <ul className="signals">
              {p.signals.map((s, i) => (
                <li key={i} className={`sev-${s.severity}`}>
                  <span className="source">{SOURCE_LABEL[s.source]}</span>
                  {s.text}
                </li>
              ))}
            </ul>
          </>
        )}
        {p.confirmedByBoth && <p className="confirmed">Confirmed by both FotMob and FPL</p>}
      </div>
    </div>
  )
}

function MatchRow({ m }: { m: IntlMatch }) {
  let involvement: string
  if (!m.played) involvement = m.onBench ? 'Unused sub' : 'Not in squad'
  else if (!m.started) involvement = `Sub on ${m.subbedOnMinute}'`
  else if (m.subbedOffMinute !== null) involvement = `Off ${m.subbedOffMinute}'`
  else involvement = 'Full match'

  const extras = [
    m.goals > 0 && `${m.goals} G`,
    m.assists > 0 && `${m.assists} A`,
    m.yellowCards > 0 && 'YC',
    m.redCards > 0 && 'RC',
    m.rating && `★ ${m.rating}`,
  ].filter(Boolean)

  return (
    <li className={m.injuredOff ? 'injured' : !m.played ? 'unused' : undefined}>
      <div className="match-main">
        <span className="minutes" title="Minutes played">
          {m.played ? `${m.minutes}'` : '–'}
        </span>
        <div>
          <p>{m.score}</p>
          <p className="muted small">
            {fmtDay(m.date)} · {m.competition}
          </p>
        </div>
      </div>
      <div className="match-meta small">
        <span>{m.injuredOff ? `Injured, off ${m.subbedOffMinute ?? '?'}'` : involvement}</span>
        {extras.length > 0 && <span className="muted">{extras.join(' · ')}</span>}
      </div>
    </li>
  )
}
