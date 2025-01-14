{strip}
	{* Display more information about the title*}
	{if $recordDriver->getPrimaryAuthor()}
		<div class="row">
			<div class="result-label col-md-3">{translate text="Author" isPublicFacing=true}</div>
			<div class="col-md-9 result-value">
				<a href='/Author/Home?author="{$recordDriver->getPrimaryAuthor()|escape:"url"}"'>{$recordDriver->getPrimaryAuthor()|highlight}</a>
			</div>
		</div>
	{/if}

	{if $showSeries}
		<div class="series row" id="seriesPlaceholder{$recordDriver->getPermanentId()}"></div>
	{/if}

	{if $showFormats}
		<div class="row">
			<div class="result-label col-md-3">{translate text='Format' isPublicFacing=true}</div>
			<div class="col-md-9 result-value">
				{implode subject=$recordDriver->getFormats() glue=", " translate=true isPublicFacing=true}
			</div>
		</div>
	{/if}

	{if $showISBNs && count($recordDriver->getISBNs()) > 0}
		<div class="row">
			<div class="result-label col-md-3">{translate text='ISBN' isPublicFacing=true}</div>
			<div class="col-md-9 result-value">
				{implode subject=$recordDriver->getISBNs() glue=", "}
			</div>
		</div>
	{/if}

	{if !empty($showArInfo) && $recordDriver->getAcceleratedReaderDisplayString()}
		<div class="row">
			<div class="result-label col-md-3">{translate text='Accelerated Reader' isPublicFacing=true} </div>
			<div class="result-value col-md-9">
				{$recordDriver->getAcceleratedReaderDisplayString()}
			</div>
		</div>
	{/if}

	{if !empty($showLexileInfo) && $recordDriver->getLexileDisplayString()}
		<div class="row">
			<div class="result-label col-md-3">{translate text='Lexile measure' isPublicFacing=true} </div>
			<div class="result-value col-md-9">
				{$recordDriver->getLexileDisplayString()}
			</div>
		</div>
	{/if}

	{if !empty($showFountasPinnell) && $recordDriver->getFountasPinnellLevel()}
		<div class="row">
			<div class="result-label col-md-3">{translate text='Fountas & Pinnell' isPublicFacing=true}</div>
			<div class="col-md-9 result-value">
				{$recordDriver->getFountasPinnellLevel()|escape}
			</div>
		</div>
	{/if}

	{include file="GroupedWork/relatedLists.tpl"}

	{include file="GroupedWork/readingHistoryIndicator.tpl"}

	<div class="row">
		<div class="result-label col-md-3">{translate text='Status' isPublicFacing=true}</div>
		<div class="col-md-9 result-value result-value-bold statusValue {$holdingsSummary.class}" id="statusValue">{translate text=$holdingsSummary.status isPublicFacing=true}</div>
	</div>
{/strip}