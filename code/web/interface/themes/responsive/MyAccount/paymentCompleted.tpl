{if $loggedIn}
	{if !empty($profile->_web_note)}
		<div class="row">
			<div id="web_note" class="alert alert-info text-center col-xs-12">{$profile->_web_note}</div>
		</div>
	{/if}
	{if !empty($accountMessages)}
	    {include file='systemMessages.tpl' messages=$accountMessages}
	{/if}
	{if !empty($ilsMessages)}
	    {include file='ilsMessages.tpl' messages=$ilsMessages}
	{/if}

	<h1>{translate text='Payment Completed'}</h1>
	{if !empty($error)}
		<div class="row">
			<div class="col-xs-12">
				<div class="alert alert-danger">{$error|translate}</div>
			</div>
		</div>
	{/if}
	{if !empty($message)}
		<div class="row">
			<div class="col-xs-12">
				<div class="alert alert-success">{$message|translate}</div>
			</div>
		</div>
	{/if}

	<div class="row">
		<div class="col-xs-12">
			<a class="btn btn-primary" href="/MyAccount/Fines">{translate text="View Fines"}</a>
		</div>
	</div>
{else}
	You must sign in to view this information. Click <a href="/MyAccount/Login">here</a> to sign in.
{/if}