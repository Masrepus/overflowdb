package io.shiftleft.overflowdb.traversals

import io.shiftleft.overflowdb.OdbNode
import org.apache.tinkerpop.gremlin.structure.Direction

trait NodeOps { this: OdbNode =>
  def adjacentNodes[A](direction: Direction, label: String): Traversal[A] =
    new Traversal(vertices(direction, label)).cast[A]
}
