// Copyright (C) 2018 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.gerrit.entities.Comment.Status;
import static java.util.stream.Collectors.toSet;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Comment;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.validators.CommentForValidation;
import com.google.gerrit.extensions.validators.CommentValidationFailure;
import com.google.gerrit.extensions.validators.CommentValidator;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.patch.PatchListCache;
import com.google.gerrit.server.patch.PatchListNotAvailableException;
import com.google.gerrit.server.plugincontext.PluginSetContext;
import com.google.gerrit.server.update.ChangeContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Collection;
import java.util.Map;

@Singleton
public class PublishCommentUtil {
  private final PatchListCache patchListCache;
  private final PatchSetUtil psUtil;
  private final CommentsUtil commentsUtil;

  @Inject
  PublishCommentUtil(
      CommentsUtil commentsUtil, PatchListCache patchListCache, PatchSetUtil psUtil) {
    this.commentsUtil = commentsUtil;
    this.psUtil = psUtil;
    this.patchListCache = patchListCache;
  }

  public void publish(
      ChangeContext ctx,
      PatchSet.Id psId,
      Collection<Comment> draftComments,
      @Nullable String tag) {
    ChangeNotes notes = ctx.getNotes();
    checkArgument(notes != null);
    if (draftComments.isEmpty()) {
      return;
    }

    Map<PatchSet.Id, PatchSet> patchSets =
        psUtil.getAsMap(notes, draftComments.stream().map(d -> psId(notes, d)).collect(toSet()));
    for (Comment draftComment : draftComments) {
      PatchSet.Id psIdOfDraftComment = psId(notes, draftComment);
      PatchSet ps = patchSets.get(psIdOfDraftComment);
      if (ps == null) {
        throw new StorageException("patch set " + psIdOfDraftComment + " not found");
      }
      draftComment.writtenOn = ctx.getWhen();
      draftComment.tag = tag;
      // Draft may have been created by a different real user; copy the current real user. (Only
      // applies to X-Gerrit-RunAs, since modifying drafts via on_behalf_of is not allowed.)
      ctx.getUser().updateRealAccountId(draftComment::setRealAuthor);
      try {
        CommentsUtil.setCommentCommitId(draftComment, patchListCache, notes.getChange(), ps);
      } catch (PatchListNotAvailableException e) {
        throw new StorageException(e);
      }
    }
    commentsUtil.putComments(ctx.getUpdate(psId), Status.PUBLISHED, draftComments);
  }

  private static PatchSet.Id psId(ChangeNotes notes, Comment c) {
    return PatchSet.id(notes.getChangeId(), c.key.patchSetId);
  }

  /**
   * Helper to run the specified set of {@link CommentValidator}-s on the specified comments.
   *
   * @return See {@link CommentValidator#validateComments(ImmutableList)}.
   */
  public static ImmutableList<CommentValidationFailure> findInvalidComments(
      PluginSetContext<CommentValidator> commentValidators,
      ImmutableList<CommentForValidation> commentsForValidation) {
    ImmutableList.Builder<CommentValidationFailure> commentValidationFailures =
        new ImmutableList.Builder<>();
    commentValidators.runEach(
        listener ->
            commentValidationFailures.addAll(listener.validateComments(commentsForValidation)));
    return commentValidationFailures.build();
  }
}
