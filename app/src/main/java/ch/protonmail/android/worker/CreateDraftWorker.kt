/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonMail.
 *
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */

package ch.protonmail.android.worker

import android.content.Context
import androidx.hilt.Assisted
import androidx.hilt.work.WorkerInject
import androidx.lifecycle.LiveData
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.protonmail.android.activities.messageDetails.repository.MessageDetailsRepository
import ch.protonmail.android.api.models.messages.receive.MessageFactory
import ch.protonmail.android.api.models.room.messages.Message
import ch.protonmail.android.api.models.room.messages.MessageSender
import ch.protonmail.android.api.utils.Fields
import ch.protonmail.android.core.UserManager
import ch.protonmail.android.domain.entity.Id
import javax.inject.Inject

internal const val KEY_INPUT_DATA_CREATE_DRAFT_MESSAGE_DB_ID = "keyCreateDraftMessageDbId"
internal const val KEY_INPUT_DATA_CREATE_DRAFT_MESSAGE_LOCAL_ID = "keyCreateDraftMessageLocalId"
internal const val KEY_INPUT_DATA_CREATE_DRAFT_MESSAGE_PARENT_ID = "keyCreateDraftMessageParentId"

internal const val KEY_OUTPUT_DATA_CREATE_DRAFT_RESULT_ERROR_ENUM = "keyCreateDraftErrorResult"

private const val INPUT_MESSAGE_DB_ID_NOT_FOUND = -1L

class CreateDraftWorker @WorkerInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val messageDetailsRepository: MessageDetailsRepository,
    private val messageFactory: MessageFactory,
    private val userManager: UserManager
) : CoroutineWorker(context, params) {


    override suspend fun doWork(): Result {
        val message = messageDetailsRepository.findMessageByMessageDbId(getInputMessageDbId())
            ?: return failureWithError(CreateDraftWorkerErrors.MessageNotFound)

        // not needed as done by the use case already (setLabels) ***************************************************************
//        message.setLocation(Constants.MessageLocationType.DRAFT.getMessageLocationTypeValue());

        val createDraftRequest = messageFactory.createDraftApiRequest(message)
        val parentMessage: Message? = null
        val inputParentId = getInputParentId()
        inputParentId?.let {
            createDraftRequest.setParentID(inputParentId);
//            draftApiModel.setAction(mActionType.getMessageActionTypeValue());
//            if(!isTransient) {
//                parentMessage = getMessageDetailsRepository().findMessageById(mParentId);
//            } else {
//                parentMessage = getMessageDetailsRepository().findSearchMessageById(mParentId);
//            }
        }

        val addressId = requireNotNull(message.addressID)
        val encryptedMessage = requireNotNull(message.messageBody)

        // TODO can be deleted as it's duplicated with just reading the message body (access same data)
//        if (!TextUtils.isEmpty(message.getMessageId())) {
//            Message savedMessage = getMessageDetailsRepository().findMessageById(message.getMessageId());
//            if (savedMessage != null) {
//                encryptedMessage = savedMessage.getMessageBody();
//            }
//        }


        val username = userManager.username
        val user = userManager.getUser(username).loadNew(username)
        user.findAddressById(Id(addressId))?.let {
            createDraftRequest.setSender(MessageSender(it.displayName?.s, it.email.s));
            createDraftRequest.addMessageBody(Fields.Message.SELF, encryptedMessage);
        }


        return Result.failure()
    }

    private fun getInputParentId(): String? {
        return inputData.getString(KEY_INPUT_DATA_CREATE_DRAFT_MESSAGE_PARENT_ID)
    }

    private fun failureWithError(error: CreateDraftWorkerErrors): Result {
        val errorData = workDataOf(KEY_OUTPUT_DATA_CREATE_DRAFT_RESULT_ERROR_ENUM to error.name)
        return Result.failure(errorData)
    }

    private fun getInputMessageDbId() =
        inputData.getLong(KEY_INPUT_DATA_CREATE_DRAFT_MESSAGE_DB_ID, INPUT_MESSAGE_DB_ID_NOT_FOUND)

    enum class CreateDraftWorkerErrors {
        SendingInProgressError,
        MessageNotFound
    }

    class Enqueuer @Inject constructor(private val workManager: WorkManager) {

        fun enqueue(message: Message, parentId: String?): LiveData<WorkInfo> {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val createDraftRequest = OneTimeWorkRequestBuilder<CreateDraftWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_INPUT_DATA_CREATE_DRAFT_MESSAGE_DB_ID to message.dbId,
                        KEY_INPUT_DATA_CREATE_DRAFT_MESSAGE_LOCAL_ID to message.messageId,
                        KEY_INPUT_DATA_CREATE_DRAFT_MESSAGE_PARENT_ID to parentId
                    )
                ).build()

            workManager.enqueue(createDraftRequest)
            return workManager.getWorkInfoByIdLiveData(createDraftRequest.id)
        }
    }

}
