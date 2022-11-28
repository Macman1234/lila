package lila.setup

import chess.Clock
import chess.format.FEN
import chess.variant.FromPosition

import lila.common.Days
import lila.game.{ GameRule, PerfPicker }
import lila.rating.PerfType
import lila.user.User

final case class OpenConfig(
    name: Option[String],
    variant: chess.variant.Variant,
    clock: Option[Clock.Config],
    days: Option[Days],
    rated: Boolean,
    position: Option[FEN],
    userIds: Option[(UserId, UserId)],
    rules: Set[GameRule] = Set.empty
):

  def perfType: Option[PerfType] = PerfPicker.perfType(chess.Speed(clock), variant, none)

  def validFen = ApiConfig.validFen(variant, position)

  def autoVariant =
    if (variant.standard && position.exists(!_.initial)) copy(variant = FromPosition)
    else this

object OpenConfig:

  def from(
      n: Option[String],
      v: Option[String],
      cl: Option[Clock.Config],
      days: Option[Days],
      rated: Boolean,
      pos: Option[FEN],
      usernames: Option[List[String]],
      rules: Option[Set[GameRule]]
  ) =
    new OpenConfig(
      name = n.map(_.trim).filter(_.nonEmpty),
      variant = chess.variant.Variant.orDefault(~v),
      clock = cl,
      days = days,
      rated = rated,
      position = pos,
      userIds = usernames.map(_.map(User.normalize)) collect { case List(w, b) =>
        (w, b)
      },
      rules = ~rules
    ).autoVariant
