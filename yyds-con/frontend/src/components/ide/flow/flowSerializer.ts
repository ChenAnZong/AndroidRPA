import type { FlowGraph, FlowNode } from '@/types';
import { NODE_DEFAULTS } from './flowTypes';

/** Serialize a FlowGraph to pretty-printed JSON */
export function serializeGraph(graph: FlowGraph): string {
  return JSON.stringify(graph, null, 2);
}

/** Deserialize JSON to FlowGraph with basic validation. Returns empty graph on error. */
export function deserializeGraph(json: string): FlowGraph {
  try {
    const obj = JSON.parse(json);
    if (
      !obj ||
      !Array.isArray(obj.nodes) ||
      !Array.isArray(obj.edges) ||
      !obj.viewport
    ) {
      return createEmptyGraph();
    }
    const graph: FlowGraph = {
      nodes: obj.nodes as FlowNode[],
      edges: obj.edges,
      viewport: {
        zoom: obj.viewport.zoom ?? 1,
        x: obj.viewport.x ?? obj.viewport.panX ?? 0,
        y: obj.viewport.y ?? obj.viewport.panY ?? 0,
      },
    };
    return graph;
  } catch {
    return createEmptyGraph();
  }
}

/** Create a new empty graph with a single start node */
export function createEmptyGraph(): FlowGraph {
  const def = NODE_DEFAULTS.start;
  return {
    nodes: [
      {
        id: 'node_start',
        type: 'start',
        label: def.label,
        x: 200,
        y: 100,
        width: def.width,
        height: def.height,
        data: { ...def.data },
        inputs: [...def.inputs],
        outputs: [...def.outputs],
      },
    ],
    edges: [],
    viewport: { zoom: 1, x: 0, y: 0 },
  };
}
