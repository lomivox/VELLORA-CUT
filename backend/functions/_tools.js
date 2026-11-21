// The read-only tools Claude can call on the connected channel.
import { yt, ToolError } from "./_google.js";

const DAY = 86400000;

function durationSeconds(iso) {
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(iso || "");
  if (!m) return null;
  return (+m[1] || 0) * 3600 + (+m[2] || 0) * 60 + (+m[3] || 0);
}

const ageDays = (iso) => Math.max(0, (Date.now() - Date.parse(iso)) / DAY);
const round1 = (n) => Math.round(n * 10) / 10;

function median(list) {
  if (list.length === 0) return 0;
  const s = list.slice().sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

function clampInt(v, min, max, fallback) {
  const n = Number.isFinite(Number(v)) ? Math.floor(Number(v)) : fallback;
  return Math.min(max, Math.max(min, n));
}

export const TOOLS = [
  {
    name: "channel_overview",
    description: "Get the connected YouTube channel's name, subscribers, total views and number of videos.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  {
    name: "recent_videos",
    description:
      "List the channel's most recent videos with views, likes, comments, length, age and views per day (newest first).",
    inputSchema: {
      type: "object",
      properties: { limit: { type: "integer", minimum: 1, maximum: 50, description: "How many videos (default 15)" } },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  {
    name: "weak_videos",
    description:
      "Find recent videos that perform far below the channel's typical views per day. Returns the weakest first, with how they compare to the median.",
    inputSchema: {
      type: "object",
      properties: {
        limit: { type: "integer", minimum: 5, maximum: 50, description: "How many recent videos to examine (default 30)" },
        min_age_days: { type: "integer", minimum: 0, maximum: 365, description: "Ignore videos newer than this (default 3)" },
        threshold: { type: "number", minimum: 0.05, maximum: 1, description: "Weak = below this fraction of the median views per day (default 0.5)" },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  {
    name: "video_details",
    description: "Get the title, description, tags and statistics of one video by its ID (useful for SEO review).",
    inputSchema: {
      type: "object",
      properties: { video_id: { type: "string", description: "YouTube video ID" } },
      required: ["video_id"],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
];

async function channelInfo(env, grantId, grant) {
  const data = await yt(env, grantId, grant, "channels", {
    part: "snippet,statistics,contentDetails",
    mine: "true",
  });
  const ch = (data.items || [])[0];
  if (!ch) throw new ToolError("No YouTube channel found for this connection");
  return ch;
}

function shapeVideo(v) {
  const views = Number((v.statistics && v.statistics.viewCount) || 0);
  const age = ageDays(v.snippet.publishedAt);
  return {
    id: v.id,
    title: v.snippet.title,
    published: v.snippet.publishedAt.slice(0, 10),
    age_days: round1(age),
    views,
    likes: Number((v.statistics && v.statistics.likeCount) || 0),
    comments: Number((v.statistics && v.statistics.commentCount) || 0),
    duration_seconds: durationSeconds(v.contentDetails && v.contentDetails.duration),
    views_per_day: round1(views / Math.max(1, age)),
    url: "https://youtu.be/" + v.id,
  };
}

async function loadRecent(env, grantId, grant, limit) {
  const ch = await channelInfo(env, grantId, grant);
  const uploads = ch.contentDetails && ch.contentDetails.relatedPlaylists && ch.contentDetails.relatedPlaylists.uploads;
  if (!uploads) return { channel: ch, videos: [] };
  const list = await yt(env, grantId, grant, "playlistItems", {
    part: "contentDetails",
    playlistId: uploads,
    maxResults: String(limit),
  });
  const ids = (list.items || []).map((i) => i.contentDetails.videoId).filter(Boolean);
  if (ids.length === 0) return { channel: ch, videos: [] };
  const details = await yt(env, grantId, grant, "videos", {
    part: "snippet,statistics,contentDetails",
    id: ids.join(","),
    maxResults: String(ids.length),
  });
  const byId = new Map((details.items || []).map((v) => [v.id, v]));
  const videos = ids.map((id) => byId.get(id)).filter(Boolean).map(shapeVideo);
  return { channel: ch, videos };
}

export async function callTool(env, grantId, grant, name, args) {
  args = args || {};

  if (name === "channel_overview") {
    const ch = await channelInfo(env, grantId, grant);
    const s = ch.statistics || {};
    return {
      channel_id: ch.id,
      title: ch.snippet.title,
      handle: ch.snippet.customUrl || null,
      created: (ch.snippet.publishedAt || "").slice(0, 10),
      subscribers: s.hiddenSubscriberCount ? "hidden" : Number(s.subscriberCount || 0),
      total_views: Number(s.viewCount || 0),
      videos: Number(s.videoCount || 0),
    };
  }

  if (name === "recent_videos") {
    const { videos } = await loadRecent(env, grantId, grant, clampInt(args.limit, 1, 50, 15));
    return { count: videos.length, videos };
  }

  if (name === "weak_videos") {
    const limit = clampInt(args.limit, 5, 50, 30);
    const minAge = clampInt(args.min_age_days, 0, 365, 3);
    const threshold = Math.min(1, Math.max(0.05, Number(args.threshold) || 0.5));
    const { videos } = await loadRecent(env, grantId, grant, limit);
    const eligible = videos.filter((v) => v.age_days >= minAge);
    if (eligible.length < 3) {
      return { note: "Not enough videos older than " + minAge + " days to compare.", examined: eligible.length };
    }
    const med = median(eligible.map((v) => v.views_per_day));
    const weak = eligible
      .filter((v) => v.views_per_day < med * threshold)
      .sort((a, b) => a.views_per_day - b.views_per_day)
      .slice(0, 10)
      .map((v) => ({ ...v, vs_median: med > 0 ? round1(v.views_per_day / med) : 0 }));
    return {
      method: "views per day since upload, compared with the median of the examined videos",
      examined: eligible.length,
      median_views_per_day: round1(med),
      threshold_fraction: threshold,
      weak_count: weak.length,
      weak_videos: weak,
    };
  }

  if (name === "video_details") {
    const id = String(args.video_id || "").trim();
    if (!/^[A-Za-z0-9_-]{6,20}$/.test(id)) throw new ToolError("video_id is not a valid YouTube video ID");
    const data = await yt(env, grantId, grant, "videos", { part: "snippet,statistics,contentDetails", id });
    const v = (data.items || [])[0];
    if (!v) throw new ToolError("Video not found");
    return {
      ...shapeVideo(v),
      description: (v.snippet.description || "").slice(0, 1500),
      tags: v.snippet.tags || [],
      category_id: v.snippet.categoryId,
      language: v.snippet.defaultAudioLanguage || v.snippet.defaultLanguage || null,
    };
  }

  throw new ToolError("Unknown tool: " + name);
}
