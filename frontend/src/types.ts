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
  live: boolean
}

export interface NextFixture {
  opponent: string
  home: boolean
  kickoff: string
  difficulty: number
}

export interface Price {
  now: number
  changeThisGw: number
  netTransfers: number
}

export type RestLevel = 'ok' | 'watch' | 'tight' | 'n/a'

export interface Rest {
  level: RestLevel
  lastIntlMatch: string | null
  clubKickoff: string | null
  restDays: number | null
  breakMinutes: number
  longTrip: string | null
  note: string
}

export type OnPitch = 'playing' | 'subbed_off' | 'bench' | 'not_in_squad' | 'unknown'

export interface LiveMatch {
  fotmobMatchId: number
  opponent: string
  home: boolean
  minute: string
  score: string | null
  onPitch: OnPitch
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
  nextFixtures: NextFixture[]
  price: Price
  rest: Rest
  live: LiveMatch | null
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

export interface LeagueOption {
  id: number
  name: string
  rank: number | null
}

export interface RivalImpact {
  entry: number
  teamName: string
  manager: string
  rank: number
  total: number
  isYou: boolean
  score: number
  injured: number
  watch: number
  withdrew: number
  tightRest: number
  away: number
  flagged: { name: string; reason: string; bench: boolean }[]
  error?: string
}

export interface LeagueReport {
  league: { id: number; name: string }
  generatedAt: string
  rivals: RivalImpact[]
  limit: number
}

export interface H2HMatch {
  kickoff: string
  home: boolean
  result: string
  minutes: number
  goals: number
  assists: number
  points: number
}

export interface H2HSeason {
  season: string
  status: 'played' | 'no-player' | 'no-opponent' | 'own-club' | 'not-met' | 'unavailable'
  club: string | null
  apps: number
  minutes: number
  goals: number
  assists: number
  points: number
  matches: H2HMatch[]
}

export interface H2HTotals {
  apps: number
  goals: number
  assists: number
  points: number
}

export interface H2HPlayer {
  id: number
  name: string
  position: string
  club: string
  bench: boolean
  total: H2HTotals
  seasons: H2HSeason[]
}

export interface Meeting {
  season: string
  kickoff: string
  home: string
  away: string
  homeGoals: number
  awayGoals: number
}

export interface Contributor {
  name: string
  goals: number
  assists: number
  mine: boolean
}

export interface MatchupSide {
  name: string
  short: string
  top: Contributor[]
}

export interface Matchup {
  kickoff: string | null
  home: MatchupSide
  away: MatchupSide
  meetings: Meeting[]
  players: H2HPlayer[]
}

export interface H2HReport {
  event: number
  seasons: string[]
  matchups: Matchup[]
  blanks: { id: number; name: string; club: string }[]
  generatedAt: string
}
