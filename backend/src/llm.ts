// Server-side-only LLM use (spec section 2, point 14): exactly two fuzzy,
// non-numeric fields — the spoiler-free hook sentence, and the 0-10 stakes
// judgment. Everything numeric (the rubric score itself) is computed in
// rubric.ts, never by the model.
//
// The hook is always a neutral, pre-game-style matchup synopsis (teams,
// storylines, rivalry, stakes) — never a description of how the game played
// out, even for completed games. Drama facts (comeback, lead changes, OT,
// buzzer-beater) are reserved for the "full breakdown" reveal in the client,
// which is gated behind its own explicit tap. There is deliberately only one
// hook per game, generated once and never regenerated based on the result.
import Anthropic from "@anthropic-ai/sdk";

const MODEL = "claude-haiku-4-5-20251001"; // cheap/fast: fits the daily call-budget cap in spec section 4

let client: Anthropic | null = null;
function getClient(): Anthropic | null {
  if (!process.env.ANTHROPIC_API_KEY) return null;
  if (!client) client = new Anthropic({ apiKey: process.env.ANTHROPIC_API_KEY });
  return client;
}

const HOOK_STAKES_TOOL: Anthropic.Tool = {
  name: "emit_hook_and_stakes",
  description: "Emit the spoiler-free hook sentence and the stakes score.",
  input_schema: {
    type: "object",
    properties: {
      hook: {
        type: "string",
        description:
          "One neutral, pre-game-style matchup synopsis sentence (teams, storylines, rivalry). " +
          "Never mention the score, the winner, or any detail of how the game played out.",
      },
      stakes: {
        type: "integer",
        minimum: 0,
        maximum: 10,
        description: "0-10 judgment of playoff/rivalry/seeding stakes, based only on matchup context.",
      },
    },
    required: ["hook", "stakes"],
  },
};

function extractToolInput(message: Anthropic.Message): { hook: string; stakes: number } | null {
  const toolUse = message.content.find((b): b is Anthropic.ToolUseBlock => b.type === "tool_use");
  if (!toolUse) return null;
  const input = toolUse.input as { hook?: unknown; stakes?: unknown };
  if (typeof input.hook !== "string" || typeof input.stakes !== "number") return null;
  return { hook: input.hook, stakes: Math.max(0, Math.min(10, Math.round(input.stakes))) };
}

export interface MatchupContext {
  away: string;
  home: string;
  awayRecord?: string;
  homeRecord?: string;
  notes?: string; // e.g. "Both teams fighting for the final play-in spot"
}

/** Fallback used when ANTHROPIC_API_KEY isn't set, so the rest of the backend is testable without a key. */
function fallbackHookAndStakes(ctx: MatchupContext): { hook: string; stakes: number } {
  return {
    hook: `${ctx.away} at ${ctx.home}.`,
    stakes: ctx.notes ? 5 : 3,
  };
}

export async function generateHookAndStakes(ctx: MatchupContext): Promise<{ hook: string; stakes: number }> {
  const anthropic = getClient();
  if (!anthropic) return fallbackHookAndStakes(ctx);

  try {
    const message = await anthropic.messages.create({
      model: MODEL,
      max_tokens: 300,
      tools: [HOOK_STAKES_TOOL],
      tool_choice: { type: "tool", name: HOOK_STAKES_TOOL.name },
      messages: [
        {
          role: "user",
          content:
            `NBA matchup: ${ctx.away}${ctx.awayRecord ? ` (${ctx.awayRecord})` : ""} at ` +
            `${ctx.home}${ctx.homeRecord ? ` (${ctx.homeRecord})` : ""}.` +
            (ctx.notes ? ` Context: ${ctx.notes}.` : "") +
            `\n\nWrite a one-line, spoiler-free "hook" sentence that sells the matchup/storylines ` +
            `(rivalry, form, stakes) — like a pre-game preview blurb. This same sentence may be shown ` +
            `whether the game is upcoming, in progress, or already finished, so it must never predict, ` +
            `state, or imply anything about the outcome. Also give a 0-10 stakes score for how much this ` +
            `game matters (playoff race, rivalry, seeding implications).`,
        },
      ],
    });

    return extractToolInput(message) ?? fallbackHookAndStakes(ctx);
  } catch (err) {
    // Never let a transient/billing/rate-limit API failure take down the whole
    // schedule request — degrade to the same no-key fallback instead.
    console.error("generateHookAndStakes: Anthropic call failed, using fallback", err);
    return fallbackHookAndStakes(ctx);
  }
}
