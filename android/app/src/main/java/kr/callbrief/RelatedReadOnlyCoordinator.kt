package kr.callbrief

import android.content.Context
import android.os.CancellationSignal
import kr.callbrief.navermail.*
import kr.callbrief.navermail.android.BuildConfig as MailBuildConfig
import kr.callbrief.navermail.android.NaverCredentialVault
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground re-read of already configured scopes. Cannot create accounts or grants. */
internal class RelatedReadOnlyCoordinator(private val context:Context,private val ownerCurrent:()->Boolean,
    private val sms:AndroidSmsReadOnlySource=AndroidSmsReadOnlySource(context),
    private val mailPolicy:AccountActionPolicy=if(MailBuildConfig.REAL_ACCOUNT_ACTIONS_APPROVED)AccountActionPolicy.approvedByHost() else AccountActionPolicy.denied(),
    private val savedAccounts:()->List<NaverCredentialVault.SavedAccount> = {
        NaverCredentialVault(context,ScopedNaverMailSettingsActivity.ownerNamespace((context as? AccountStorageContext)?.ownerId.orEmpty())).accounts()
    },
    private val credentials:(AccountBinding)->SecretChars={account->
        NaverCredentialVault(context,account.ownerId,if(MailBuildConfig.REAL_ACCOUNT_ACTIONS_APPROVED)AccountActionPolicy.approvedByHost() else AccountActionPolicy.denied()).credential(account)
    },
    private val mailbox:ReadOnlyMailbox=NaverImapMailbox(mailPolicy)
):AutoCloseable {
    private val cancelled=AtomicBoolean(false)
    private val signal=CancellationSignal()
    @Volatile private var intake:MailIntake?=null
    private fun current()=!cancelled.get()&&ownerCurrent()
    fun scanSms(id:String):Pair<Int,Boolean> {
        RelatedReadOnlyStore(context).use { store->
            val scope=store.smsScope(id) ?: error("SMS_SCOPE_NOT_SELECTED")
            val authorized={current()&&store.current(scope)}
            check(authorized()) { "RELATED_SCOPE_CHANGED" }
            val batch=sms.read(scope,authorized,signal)
            return store.storeSms(scope,batch,authorized) to batch.incomplete
        }
    }
    fun scanMail(id:String):ScanBatch {
        check(current()) { "RELATED_READ_CANCELLED" }
        mailPolicy.requireApproved() // Before credential decryption or any socket, also for saved accounts.
        RelatedReadOnlyStore(context).use { store->
            val binding=store.mailBinding(id) ?: error("MAIL_SCOPE_NOT_SELECTED")
            val authorized={current()&&store.currentMail(binding)}
            check(authorized()) { "RELATED_SCOPE_CHANGED" }
            val saved=savedAccounts().singleOrNull { it.account==binding.selected.account&&it.scope?.fingerprint==binding.savedScopeSha&&it.state==ConnectionState.VERIFY_REQUIRED }
                ?: error("MAIL_SAVED_SCOPE_CHANGED")
            check(saved.account!=null&&authorized()) { "RELATED_SCOPE_CHANGED" }
            val inbox=WorkflowNaverInbox(context,binding.selected.account.ownerId,id,authorized)
            val run=MailIntake(mailPolicy,mailbox,inbox);intake=run
            val ticket=run.select(binding.selected)
            check(authorized()) { "RELATED_SCOPE_CHANGED" }
            var batch=run.scan(ticket,credentials(binding.selected.account))
            check(authorized()) { "RELATED_SCOPE_CHANGED" }
            if(binding.bodies) {
                val refs=batch.candidates.filter { it.relation==Relation.CANDIDATE&&MailIntake.plainEligible(it.header)&&it.duplicateOf==null }.take(10).map{it.header.ref}.toSet()
                if(refs.isNotEmpty())batch=run.readSelected(batch,refs,credentials(binding.selected.account))
            }
            check(authorized()) { "RELATED_SCOPE_CHANGED" }
            return batch
        }
    }
    override fun close() { cancelled.set(true);signal.cancel();intake?.stopForBackground();intake=null }
}
