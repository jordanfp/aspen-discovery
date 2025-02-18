<?php

require_once ROOT_DIR . '/RecordDrivers/RecordInterface.php';
require_once ROOT_DIR . '/RecordDrivers/GroupedWorkSubDriver.php';
require_once ROOT_DIR . '/sys/Hoopla/HooplaExtract.php';
class HooplaRecordDriver extends GroupedWorkSubDriver {
	private $id;
	/** @var HooplaExtract */
	private $hooplaExtract;
	private $hooplaRawMetadata;
	private $valid;
	/**
	 * Constructor.  We build the object using data from the Hoopla records stored on disk.
	 * Will be similar to a MarcRecord with slightly different functionality
	 *
	 * @param string $recordId
	 * @param  GroupedWork $groupedWork;
	 * @access  public
	 */
	public function __construct($recordId, $groupedWork = null) {
		$this->id = $recordId;

		$this->hooplaExtract = new HooplaExtract();
		$this->hooplaExtract->hooplaId = $recordId;
		if ($this->hooplaExtract->find(true)) {
			$this->valid = true;
			$this->hooplaRawMetadata = json_decode($this->hooplaExtract->rawResponse);
		} else {
			$this->valid = false;
			$this->hooplaExtract = null;
		}
		if ($this->valid){
			parent::__construct($groupedWork);
		}
	}

	public function getIdWithSource(){
		return 'hoopla:' . $this->id;
	}

	/**
	 * Load the grouped work that this record is connected to.
	 */
	public function loadGroupedWork() {
		if ($this->groupedWork == null){
			require_once ROOT_DIR . '/sys/Grouping/GroupedWorkPrimaryIdentifier.php';
			require_once ROOT_DIR . '/sys/Grouping/GroupedWork.php';
			$groupedWork = new GroupedWork();
			$query = "SELECT grouped_work.* FROM grouped_work INNER JOIN grouped_work_primary_identifiers ON grouped_work.id = grouped_work_id WHERE type='hoopla' AND identifier = '" . $this->getUniqueID() . "'";
			$groupedWork->query($query);

			if ($groupedWork->getNumResults() == 1){
				$groupedWork->fetch();
				$this->groupedWork = clone $groupedWork;
			}
		}
	}

	public function getModule() : string
	{
		return 'Hoopla';
	}

	/**
	 * Assign necessary Smarty variables and return a template name to
	 * load in order to display the full record information on the Staff
	 * View tab of the record view page.
	 *
	 * @access  public
	 * @return  string              Name of Smarty template file to display.
	 */
	public function getStaffView()
	{
		global $interface;
		$this->getGroupedWorkDriver()->assignGroupedWorkStaffView();

		$interface->assign('bookcoverInfo', $this->getBookcoverInfo());

		$interface->assign('hooplaExtract', $this->hooplaRawMetadata);
		return 'RecordDrivers/Hoopla/staff-view.tpl';
	}

	/**
	 * Get the full title of the record.
	 *
	 * @return  string
	 */
	public function getTitle()
	{
		return $this->hooplaExtract->title;
	}

	/**
	 * The Table of Contents extracted from the record.
	 * Returns null if no Table of Contents is available.
	 *
	 * @access  public
	 * @return  array              Array of elements in the table of contents
	 */
	public function getTableOfContents()
	{
		$tableOfContents = array();
		$segments = $this->hooplaRawMetadata->segments;
		if (!empty($segments)){
			foreach ($segments as $segment){
				$label = $segment->name;
				if ($segment->seconds){
					$hours = floor($segment->seconds / 3600);
					$mins = floor($segment->seconds / 60 % 60);
					$secs = floor($segment->seconds % 60);

					if ($hours > 0){
						$label .= sprintf(' (%01d:%02d:%02d)', $hours, $mins, $secs);
					}else{
						$label .= sprintf(' (%01d:%02d)', $mins, $secs);
					}

				}
				$tableOfContents[] = $label;
			}
		}
		return $tableOfContents;
	}

	/**
	 * Return the unique identifier of this record within the Solr index;
	 * useful for retrieving additional information (like tags and user
	 * comments) from the external MySQL database.
	 *
	 * @access  public
	 * @return  string              Unique identifier.
	 */
	public function getUniqueID()
	{
		return $this->id;
	}

	public function getDescription()
	{
		if (!empty($this->hooplaRawMetadata->synopsis)) {
			return $this->hooplaRawMetadata->synopsis;
		}else{
			return "";
		}
	}

	public function getMoreDetailsOptions(){
		global $interface;

		$isbn = $this->getCleanISBN();

		//Load table of contents
		$tableOfContents = $this->getTableOfContents();
		$interface->assign('tableOfContents', $tableOfContents);

		//Load more details options
		$moreDetailsOptions = $this->getBaseMoreDetailsOptions($isbn);
		//Other editions if applicable (only if we aren't the only record!)
		$relatedRecords = $this->getGroupedWorkDriver()->getRelatedRecords();
		if (count($relatedRecords) > 1){
			$interface->assign('relatedManifestations', $this->getGroupedWorkDriver()->getRelatedManifestations());
			$interface->assign('workId',$this->getGroupedWorkDriver()->getPermanentId());
			$moreDetailsOptions['otherEditions'] = array(
				'label' => 'Other Editions and Formats',
				'body' => $interface->fetch('GroupedWork/relatedManifestations.tpl'),
				'hideByDefault' => false
			);
		}

		$moreDetailsOptions['moreDetails'] = array(
			'label' => 'More Details',
			'body' => $interface->fetch('Hoopla/view-more-details.tpl'),
		);
		$this->loadSubjects();
		$moreDetailsOptions['subjects'] = array(
			'label' => 'Subjects',
			'body' => $interface->fetch('RecordDrivers/Hoopla/view-subjects.tpl'),
		);
		$moreDetailsOptions['citations'] = array(
			'label' => 'Citations',
			'body' => $interface->fetch('Record/cite.tpl'),
		);
		if ($interface->getVariable('showStaffView')){
			$moreDetailsOptions['staff'] = array(
				'label' => 'Staff View',
				'body' => $interface->fetch($this->getStaffView()),
			);
		}

		return $this->filterAndSortMoreDetailsOptions($moreDetailsOptions);
	}

	public function getISBNs()
	{
		$isbns = [];
		if (!empty($this->hooplaRawMetadata->isbn)) {
			$isbns[] = $this->hooplaRawMetadata->isbn;
		}
		return $isbns;
	}

	public function getISSNs()
	{
		return array();
	}

	protected $_actions = null;
	function getRecordActions($relatedRecord, $recordAvailable, $recordHoldable, $volumeData = null){
		if ($this->_actions === null) {
			$this->_actions = array();
			//Check to see if the title is on hold or checked out to the patron.
			$loadDefaultActions = true;
			if (UserAccount::isLoggedIn()) {
				$user = UserAccount::getActiveUserObj();
				$this->_actions = array_merge($this->_actions, $user->getCirculatedRecordActions('hoopla', $this->id));
				$loadDefaultActions = count($this->_actions) == 0;
			}

			if ($loadDefaultActions) {
				/** @var Library $searchLibrary */
				$searchLibrary = Library::getSearchLibrary();
				if ($searchLibrary->hooplaLibraryID > 0) { // Library is enabled for Hoopla patron action integration
					$id = $this->id;
					$title = translate(['text'=>'Check Out Hoopla','isPublicFacing'=>true]);
					$this->_actions[] = array(
						'onclick' => "return AspenDiscovery.Hoopla.getCheckOutPrompts('$id')",
						'title' => $title,
						'type' => 'hoopla_checkout'
					);
				}
			}
		}

		return $this->_actions;
	}

	/**
	 * Returns an array of contributors to the title, ideally with the role appended after a pipe symbol
	 * @return array
	 */
	function getContributors()
	{
		$contributors = array();
		if (isset($this->hooplaRawMetadata->artists)){
			$authors = $this->hooplaRawMetadata->artists;
			foreach ($authors as $author) {
				//TODO: Reverse name?
				$contributors[] = $author->name . '|' . ucwords($author->relationship);
			}
		}
		return $contributors;
	}

	/**
	 * Get the edition of the current record.
	 *
	 * @access  protected
	 * @return  array
	 */
	function getEditions()
	{
		// No specific information provided by Hoopla
		return array();
	}

	/**
	 * @return array
	 */
	function getFormats()
	{
		if ($this->hooplaExtract->kind == "MOVIE" || $this->hooplaExtract->kind == "TELEVISION") {
			return ['eVideo'];
		} elseif ($this->hooplaExtract->kind == "AUDIOBOOK"){
			return ['eAudiobook'];
		} elseif ($this->hooplaExtract->kind == "EBOOK"){
			return ['eBook'];
		} elseif ($this->hooplaExtract->kind == "ECOMIC"){
			return ['eComic'];
		} elseif ($this->hooplaExtract->kind == "MUSIC"){
			return ['eMusic'];
		} else {
			return ['eBook'];
		}
	}

	/**
	 * Get an array of all the format categories associated with the record.
	 *
	 * @return  array
	 */
	function getFormatCategory()
	{
		if ($this->hooplaExtract->kind == "AUDIOBOOK") {
			return ['eBook', 'Audio Books'];
		}else if ($this->hooplaExtract->kind == "MOVIE" || $this->hooplaExtract->kind == "TELEVISION") {
			return ['Movies'];
		}else if ($this->hooplaExtract->kind == "MUSIC") {
			return ['Music'];
		} else {
			return ['eBook'];
		}
	}

	public function getLanguage()
	{
		return ucfirst(strtolower($this->hooplaRawMetadata->language));
	}

	public function getNumHolds(){
		return 0;
	}

	/**
	 * @return array
	 */
	function getPlacesOfPublication()
	{
		//Not provided within the metadata
		return array();
	}

	/**
	 * Returns the primary author of the work
	 * @return String
	 */
	function getPrimaryAuthor()
	{
		return $this->getAuthor();
	}

	public function getAuthor(){
		if (!empty($this->hooplaRawMetadata->artist)){
			return $this->hooplaRawMetadata->artist;
		}else{
			return '';
		}
	}

	/**
	 * @return array
	 */
	function getPublishers()
	{
		return [$this->hooplaRawMetadata->publisher];
	}

	/**
	 * @return array
	 */
	function getPublicationDates()
	{
		return [$this->hooplaRawMetadata->year];
	}

	public function getRecordType()
	{
		return 'hoopla';
	}

	function getRelatedRecord() {
		$id = 'hoopla:' . $this->id;
		return $this->getGroupedWorkDriver()->getRelatedRecord($id);
	}

	public function getSemanticData() {
		// Schema.org
		// Get information about the record
		require_once ROOT_DIR . '/RecordDrivers/LDRecordOffer.php';
		$relatedRecord = $this->getGroupedWorkDriver()->getRelatedRecord($this->getIdWithSource());
		if ($relatedRecord != null) {
			$linkedDataRecord = new LDRecordOffer($this->getRelatedRecord());
			$semanticData [] = array(
				'@context' => 'http://schema.org',
				'@type' => $linkedDataRecord->getWorkType(),
				'name' => $this->getTitle(),
				'creator' => $this->getPrimaryAuthor(),
				'bookEdition' => $this->getEditions(),
				'isAccessibleForFree' => true,
				'image' => $this->getBookcoverUrl('medium', true),
				"offers" => $linkedDataRecord->getOffers()
			);

			global $interface;
			$interface->assign('og_title', $this->getTitle());
			$interface->assign('og_description', $this->getDescription());
			$interface->assign('og_type', $this->getGroupedWorkDriver()->getOGType());
			$interface->assign('og_image', $this->getBookcoverUrl('medium', true));
			$interface->assign('og_url', $this->getAbsoluteUrl());
			return $semanticData;
		}else{
			return null;
		}
	}

	/**
	 * Returns title without subtitle
	 *
	 * @return string
	 */
	function getShortTitle()
	{
		return $this->getTitle();
	}

	/**
	 * Returns subtitle
	 *
	 * @return string
	 */
	function getSubtitle()
	{
		return "";
	}

	function isValid(){
		return $this->valid;
	}

	function loadSubjects()
	{
		$subjects = [];
		if ($this->hooplaRawMetadata->genres) {
			$subjects = $this->hooplaRawMetadata->genres;
		}
		global $interface;
		$interface->assign('subjects', $subjects);
	}

	function getActions() {
		//TODO: If this is added to the related record, pass in the value
		$actions = array();

		/** @var Library $searchLibrary */
		$searchLibrary = Library::getSearchLibrary();
		if ($searchLibrary->hooplaLibraryID > 0) { // Library is enabled for Hoopla patron action integration
			$title = translate(['text'=>'Check Out Hoopla','isPublicFacing'=>true]);
			$actions[] = array(
				'onclick' => "return AspenDiscovery.Hoopla.getCheckOutPrompts('{$this->id}')",
				'title'   => $title
			);

		} else {
			$actions[] = $this->getAccessLink();
		}

		return $actions;
	}

	public function getAccessLink()
	{
		$title = translate(['text' => 'hoopla_url_action', 'isPublicFacing'=>true]);
		$accessLink = array(
			'url' => $this->hooplaRawMetadata->url,
			'title' => $title,
			'requireLogin' => false,
		);
		return $accessLink;
	}

	/**
	 * Get an array of physical descriptions of the item.
	 *
	 * @access  protected
	 * @return  array
	 */
	public function getPhysicalDescriptions()
	{
		$physicalDescriptions = [];
		if (!empty($this->hooplaRawMetadata->duration)){
			$physicalDescriptions[] = $this->hooplaRawMetadata->duration;
		}
		return $physicalDescriptions;
	}

	function getHooplaCoverUrl(){
		return $this->hooplaRawMetadata->coverImageUrl;
	}

	function getStatusSummary()
	{
		$relatedRecord = $this->getRelatedRecord();
		$statusSummary = array();
		if ($relatedRecord == null){
			$statusSummary['status'] = "Unavailable";
			$statusSummary['available'] = false;
			$statusSummary['class'] = 'unavailable';
			$statusSummary['showPlaceHold'] = false;
			$statusSummary['showCheckout'] = false;
		}else{
			$statusSummary['status'] = "Available from Hoopla";
			$statusSummary['available'] = true;
			$statusSummary['class'] = 'available';
			$statusSummary['showPlaceHold'] = false;
			$statusSummary['showCheckout'] = true;
		}
		return $statusSummary;
	}
}