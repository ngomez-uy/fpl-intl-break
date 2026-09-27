// Mirrors backend/src/analyze.ts. Keep in sync until the two share a package.
export type Source = 'fotmob' | 'fpl'
export type Severity = 'high' | 'medium' | 'info'
export type Risk = 'red' | 'amber' | 'green'

export interface Signal {
  source: Source
  severity: Severity
  text: string
}

export interface IntlMatch {
  fotmobMatchId: number
  date: string
  competition: string
  team: string
  opponent: string
  home: boolean
  score: string
  played: boolean
  started: boolean
  onBench: boolean
  minutes: number
  goals: number
  assists: number
  yellowCards: number
  redCards: number
  rating: string | null
  subbedOnMinute: number | null
  subbedOffMinute: number | null
  offReason: string | null
  injuredOff: boolean
}

export type CallUpStatus = 'called' | 'withdrawn' | 'not_called' | 'unknown'

export interface SourceCheck {
  source: string
  status: CallUpStatus
  detail: string
  url?: string
}

export interface CallUp {
  status: CallUpStatus
  checks: SourceCheck[]
  agreement: 'agree' | 'conflict' | 'single'
}

export type Involvement = 'started' | 'sub' | 'bench' | 'absent' | 'upcoming'

export interface BreakMatch {
  fotmobMatchId: number
  date: string
  opponent: string
  home: boolean
  competition: string
  finished: boolean
  score: string | null
  involvement: Involvement
  minutes: number | null
}

export interface PlayerReport {
  fplId: number
  pickPosition: number
  name: string
  webName: string
  club: string
  position: string
  squadRole: 'starter' | 'bench'
  isCaptain: boolean
  isViceCaptain: boolean
  fotmob: { fotmobId: number | null; fotmobName?: string; confidence: string }
  nationalTeam: string | null
  nation: { fotmobTeamId: number; name: string; capped: boolean } | null
  callUp: CallUp
  breakFixtures: BreakMatch[]
  matches: IntlMatch[]
  fpl: { status: string; news: string; newsAdded: string | null; chance: number | null }
  fotmobInjury: { name: string; expectedReturn: string | null; lastUpdated: string | null } | null
  signals: Signal[]
  risk: Risk
  confirmedByBoth: boolean
}

export interface DataSource {
  id: string
  name: string
  url: string
  usedFor: string[]
  status: 'live' | 'planned'
  oldestDataAt: string | null
}

export interface TeamReport {
  team: { id: number; name: string; manager: string; picksFromEvent: number }
  window: { from: string; to: string; afterEvent: number; beforeEvent: number }
  generatedAt: string
  players: PlayerReport[]
  sources: DataSource[]
}
