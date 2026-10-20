# Venue route graph

Navigation only follows paths you define. It never draws a straight line between rooms. The
only straight legs are short, bounded ones: from the user to the nearest walkway (≤ 3 m) and
from the walkway to a saved destination (≤ 2 m). See `RoutingPolicy`.

## File
`app/src/main/assets/venue_graph.json`. It ships **empty**, so navigation reports "No route graph
configured" until you map your venue. A complete example is in
[`docs/graph/venue_graph.example.json`](graph/venue_graph.example.json).

```json
{
  "formatVersion": 1,
  "venueId": "default-venue",        // must match DefaultVenue
  "frameVersion": 1,                 // venue frame version the coordinates were measured in
  "nodes": [
    { "id": "j1", "type": "JUNCTION", "floor": "ground", "x": 0, "z": 10, "name": "optional" }
  ],
  "edges": [
    { "from": "entrance", "to": "j1",
      "bidirectional": true,         // false = one-way from → to
      "attachable": true,            // false = never snap users/destinations onto this edge
      "blocked": false,              // true = temporarily closed
      "length": 12.5 }               // optional walking length if not straight
  ]
}
```
Node types: `JUNCTION`, `CORRIDOR` (bend or intermediate point), `ENTRANCE` (door), `ROOM`,
`DESTINATION`. Coordinates are metres in the venue frame ([COORDINATE_FRAMES.md](COORDINATE_FRAMES.md)):
origin at the marker, +Z straight out from a wall marker, +X to the **left** when you face out from the
marker (the frame is right-handed with +Y up). `y` is optional and ignored for routing.

## Measuring nodes with the app
1. Scan the marker in *Add destination in AR*.
2. Stand at each junction, doorway, corridor bend and room point, then tap the floor there and save a
   temporary destination such as `node j1`.
3. The home list shows each point's `x` and `z`. Copy them into the JSON.
4. Delete the temporary destinations. Rebuild and install the app.

Rules of thumb:
- Each edge must be a straight, walkable line. Add `CORRIDOR` nodes at bends.
- Connect rooms through an `ENTRANCE` node at the doorway, never straight to a corridor node through a wall.
- Mark corridors that run right behind a thin wall from a room as `"attachable": false`, so destinations in
  that room are not snapped onto the wrong side.
- If you move the marker, increment `frameVersion` in the venue and re-measure the graph. A mismatch disables navigation.

## Validation
On load, the app checks that IDs are unique, edges reference existing nodes, there are no self-loops
or near-zero-length edges, there are no duplicate edges, there are no cross-floor edges (multi-floor
routing is not supported yet), and `venueId`/`frameVersion` match. The home screen shows the result.

## Behaviour
- Route: Dijkstra over the graph, with the user and destination attached to their nearest
  attachable, unblocked edge.
- Instructions: STRAIGHT at the start, then TURN_LEFT/TURN_RIGHT where the heading changes by at least 30°
  (sharp at 120° or more), then ARRIVE. Very short segments are merged so that no turn is invented from noise.
- Deviation: the user is off route when more than 2 m plus the localization error from the path. After 3 s off
  route, the route is re-planned from the current position. If no valid route exists from there, the state
  stays OffRoute with the reason.
- Arrival: within 1 m in a straight line **and** within 2 m along the route, with localization error ≤ 0.75 m,
  for 1 s.
- No reliable localization (marker not scanned in this session, lost, interrupted, or low confidence):
  guidance is suspended. No arrival and no deviation is reported.
