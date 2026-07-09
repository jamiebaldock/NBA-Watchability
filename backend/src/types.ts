// Mirrors the mobile client's Game data class (mobile/app/.../data/Game.kt) and
// the JSON contract in nba-watchability-spec.md section 5. Keep field names in sync.

export type GameStatus = "final" | "live" | "upcoming";

export type StarPerformance = "historic" | "great" | "good" | null;

export interface GameJson {
  a: string;
  h: string;
  al?: string;
  hl?: string;
  stt: GameStatus;
  utc: string;
  lg: "nba" | "summer";
  q?: number;
  clk?: string;
  m?: number;
  cb?: number;
  lc?: number;
  ot: number;
  c5: boolean;
  lcf: boolean;
  fp: boolean;
  bz: boolean;
  st: StarPerformance;
  sk?: number;
  hook: string;
  pitch?: string;
  score?: number;
  score_visible: boolean;
}

// Raw rubric inputs derived from play-by-play + box score, before the LLM
// fields (hook/stakes) and before the score_visible gating rule are applied.
export interface RubricInputs {
  status: GameStatus;
  period?: number;
  clock?: string;
  finalMargin?: number;
  largestDeficitOvercome?: number;
  leadChanges?: number;
  overtimePeriods: number;
  closeInFinalTwoMin: boolean;
  leadChangeInFinalMin: boolean;
  decidedOnFinalPossession: boolean;
  buzzerBeater: boolean;
  starPerformance: StarPerformance;
}

export interface ScoredGame extends RubricInputs {
  score?: number;
  scoreVisible: boolean;
}
