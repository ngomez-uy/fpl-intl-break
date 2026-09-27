import "dotenv/config";
import cors from "cors";
import express from "express";
import { buildReport } from "./analyze.js";

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

const port = Number(process.env.PORT ?? 4000);
app.listen(port, () => console.log(`Backend listening on http://localhost:${port}`));
