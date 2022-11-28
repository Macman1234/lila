package lila.relation

import reactivemongo.api.bson.*
import reactivemongo.api.ReadPreference
import org.joda.time.DateTime

import lila.db.dsl.{ *, given }
import lila.user.User

final private class RelationRepo(coll: Coll, userRepo: lila.user.UserRepo)(using
    ec: scala.concurrent.ExecutionContext
):

  import RelationRepo.*

  def following(userId: ID) = relating(userId, Follow)

  def blockers(userId: ID) = relaters(userId, Block)
  def blocking(userId: ID) = relating(userId, Block)

  def freshFollowersFromSecondary(userId: ID): Fu[List[UserId]] =
    coll
      .aggregateOne(readPreference = ReadPreference.secondaryPreferred) { implicit framework =>
        import framework.*
        Match($doc("u2" -> userId, "r" -> Follow)) -> List(
          PipelineOperator(
            $lookup.pipeline(
              from = userRepo.coll,
              as = "follower",
              local = "u1",
              foreign = "_id",
              pipe = List(
                $doc("$match"   -> $expr($doc("$gt" -> $arr("$seenAt", DateTime.now.minusDays(10))))),
                $doc("$project" -> $id(true))
              )
            )
          ),
          Match("follower" $ne $arr()),
          Group(BSONNull)("ids" -> PushField("u1"))
        )
      }
      .map(~_.flatMap(_.getAsOpt[List[UserId]]("ids")))

  def followingLike(userId: ID, term: String): Fu[List[ID]] =
    User.validateId(term) ?? { valid =>
      coll.secondaryPreferred.distinctEasy[ID, List](
        "u2",
        $doc(
          "u1" -> userId,
          "u2" $startsWith valid,
          "r" -> Follow
        )
      )
    }

  private def relaters(
      userId: ID,
      relation: Relation,
      rp: ReadPreference = ReadPreference.primary
  ): Fu[Set[ID]] =
    coll
      .distinctEasy[ID, Set](
        "u1",
        $doc(
          "u2" -> userId,
          "r"  -> relation
        ),
        rp
      )

  private def relating(userId: ID, relation: Relation): Fu[Set[ID]] =
    coll.distinctEasy[ID, Set](
      "u2",
      $doc(
        "u1" -> userId,
        "r"  -> relation
      )
    )

  def follow(u1: ID, u2: ID): Funit   = save(u1, u2, Follow)
  def unfollow(u1: ID, u2: ID): Funit = remove(u1, u2)
  def block(u1: ID, u2: ID): Funit    = save(u1, u2, Block)
  def unblock(u1: ID, u2: ID): Funit  = remove(u1, u2)

  def unfollowMany(u1: ID, u2s: Iterable[ID]): Funit =
    coll.delete.one($inIds(u2s map { makeId(u1, _) })).void

  def unfollowAll(u1: ID): Funit = coll.delete.one($doc("u1" -> u1)).void

  private def save(u1: ID, u2: ID, relation: Relation): Funit =
    coll.update
      .one(
        $id(makeId(u1, u2)),
        $doc("u1" -> u1, "u2" -> u2, "r" -> relation),
        upsert = true
      )
      .void

  def remove(u1: ID, u2: ID): Funit = coll.delete.one($id(makeId(u1, u2))).void

  def drop(userId: ID, relation: Relation, nb: Int) =
    coll
      .find(
        $doc("u1" -> userId, "r" -> relation),
        $doc("_id" -> true).some
      )
      .cursor[Bdoc]()
      .list(nb)
      .dmap {
        _.flatMap { _.string("_id") }
      } flatMap { ids =>
      coll.delete.one($inIds(ids)).void
    }

object RelationRepo:

  def makeId(u1: ID, u2: ID) = s"$u1/$u2"
