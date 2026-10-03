import "dotenv/config";
import cors from "cors";
import express from "express";
import { buildReport } from "./analyze.js";
import { headToHead } from "./h2h.js";
import { leagueReport, teamLeagues } from "./league.js";

const app = express();
app.use(cors());

app.get("/api/health", (_req, res) => {
  res.json({ ok: true });
});

// GET /api/team/:id/break[?from=ISO&to=ISO]
app.get("/api/team/:id/break", async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id) || id <= 0) {
    res.status(400).json({ error: "Team ID must be a positive number" });
    return;
  }
  const { from, to } = req.query;
  try {
    const report = await buildReport(id, {
      ...(typeof from === "string" && { from: new Date(from).toISOString() }),
      ...(typeof to === "string" && { to: new Date(to).toISOString() }),
    });
    res.json(report);
  } catch (err) {
    const message = (err as Error).message;
    const status = /-> 404/.test(message) ? 404 : 502;
    res.status(status).json({ error: status === 404 ? `FPL team ${id} not found` : message });
  }
});

const positiveInt = (v: unknown) => {
  const n = Number(v);
  return Number.isInteger(n) && n > 0 ? n : null;
};

// GET /api/team/:id/leagues — the team's private mini-leagues
app.get("/api/team/:id/leagues", async (req, res) => {
  const id = positiveInt(req.params.id);
  if (!id) return void res.status(400).json({ error: "Team ID must be a positive number" });
  try {
    res.json(await teamLeagues(id));
  } catch (err) {
    res.status(502).json({ error: (err as Error).message });
  }
});

// GET /api/league/:id/break?team=<your id>&limit=<top N, max 30>
app.get("/api/league/:id/break", async (req, res) => {
  const id = positiveInt(req.params.id);
  if (!id) return void res.status(400).json({ error: "League ID must be a positive number" });
  const limit = Math.min(positiveInt(req.query.limit) ?? 10, 30);
  try {
    res.json(await leagueReport(id, positiveInt(req.query.team), limit));
  } catch (err) {
    const message = (err as Error).message;
    res.status(/-> 404/.test(message) ? 404 : 502).json({ error: /-> 404/.test(message) ? `League ${id} not found` : message });
  }
});

// GET /api/team/:id/h2h — squad players' record against their next Premier League opponent
app.get("/api/team/:id/h2h", async (req, res) => {
  const id = positiveInt(req.params.id);
  if (!id) return void res.status(400).json({ error: "Team ID must be a positive number" });
  try {
    res.json(await headToHead(id));
  } catch (err) {
    const message = (err as Error).message;
    res.status(/-> 404/.test(message) ? 404 : 502).json({ error: /-> 404/.test(message) ? `FPL team ${id} not found` : message });
  }
});

const port = Number(process.env.PORT ?? 4000);
app.listen(port, () => console.log(`Backend listening on http://localhost:${port}`));
