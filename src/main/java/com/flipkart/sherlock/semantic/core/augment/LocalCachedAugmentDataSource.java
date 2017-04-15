package com.flipkart.sherlock.semantic.core.augment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.sherlock.semantic.dao.mysql.AugmentationDao;
import com.flipkart.sherlock.semantic.dao.mysql.RawQueriesDao;
import com.flipkart.sherlock.semantic.dao.mysql.entity.AugmentationEntities.*;
import com.flipkart.sherlock.semantic.util.MapUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Created by anurag.laddha on 12/04/17.
 */

//TODO data source can have interface

@Slf4j
public class LocalCachedAugmentDataSource {

    private LoadingCache<String, TermAlternativesWrapper> augmentCache;
    private AugmentationDao augmentationDao;
    private RawQueriesDao rawQueriesDao;
    private ExecutorService executorService;
    private ObjectMapper objectMapper;

    @Inject
    public LocalCachedAugmentDataSource(AugmentationDao augmentationDao, RawQueriesDao rawQueriesDao, ExecutorService executorService,
                                        ObjectMapper objectMapper, int cacheExpireSec) {
        this.augmentationDao = augmentationDao;
        this.rawQueriesDao = rawQueriesDao;
        this.executorService = executorService;
        this.objectMapper = objectMapper;
        DataLoader dataLoader = new DataLoader(augmentationDao, rawQueriesDao, executorService, objectMapper);
        this.augmentCache = CacheBuilder.newBuilder().maximumSize(10).refreshAfterWrite(cacheExpireSec, TimeUnit.SECONDS).build(dataLoader);
    }

    /**
     * Data coming from multiple tables, for different type of replacements, prevent map merges
     */

    static class TermAlternativesWrapper {
        @Getter
        private Map<String, Set<AugmentAlternative>> termToAlternativesMap;
        @Getter
        private Map<String, Set<AugmentAlternative>> queryToAlternativesMap;

        public TermAlternativesWrapper() {
            this.termToAlternativesMap = new HashMap<>();
            this.queryToAlternativesMap = new HashMap<>();
        }

        public void addTermAlternatives(String term, Set<AugmentAlternative> alternatives){
            MapUtils.addEntriesToTargetMapValueSet(this.termToAlternativesMap, convertToKey(term), alternatives);
        }

        public void addQueryAlternatives(String query, Set<AugmentAlternative> alternatives){
            MapUtils.addEntriesToTargetMapValueSet(this.queryToAlternativesMap, convertToKey(query), alternatives);
        }

        public Set<AugmentAlternative> getTermAlternatives(String term){
            return this.termToAlternativesMap != null ? this.termToAlternativesMap.get(convertToKey(term)) : null;
        }

        public Set<AugmentAlternative> getQueryAlternatives(String query){
            return this.queryToAlternativesMap != null ? this.queryToAlternativesMap.get(convertToKey(query)) : null;
        }
    }


    /**
     * Type of augmentation data
     * Need not be visible to the external world
     */
    private static enum AugInfoType {
        TermToTerm, QueryToQuery, Negatives
    }

    /**
     * Cache data loader
     * Need not be visible to the external world
     */
    static class DataLoader extends CacheLoader<String, TermAlternativesWrapper>{

        private AugmentationDao augmentationDao;
        private RawQueriesDao rawQueriesDao;
        private ExecutorService executorService;
        private ObjectMapper objectMapper;

        //TODO this can come from config
        private static final Set<String> stopWordsSet = Sets.newHashSet("&", "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in", "into", "is", "it", "no",
            "not", "of", "on", "or", "such", "that", "the", "their", "then", "there", "these", "they", "this", "to", "was", "will", "with");

        public DataLoader(AugmentationDao augmentationDao, RawQueriesDao rawQueriesDao, ExecutorService executorService,
                          ObjectMapper objectMapper) {
            this.augmentationDao = augmentationDao;
            this.rawQueriesDao = rawQueriesDao;
            this.executorService = executorService;
            this.objectMapper = objectMapper;
        }

        @Override
        public TermAlternativesWrapper load(String s) throws Exception {
            return null;
        }

        @Override
        public ListenableFuture<TermAlternativesWrapper> reload(String key, TermAlternativesWrapper oldValue) throws Exception {
            return super.reload(key, oldValue);
        }


        private Set<String> getStopwords(){
            //TODO get from search config if we need to 'shouldNegateStopWordsInAugment'.
            return stopWordsSet;
        }

        @VisibleForTesting
        TermAlternativesWrapper getSynonymAugmentations() {
            /**
             * Replace type synonym
             *      term --> rep1, rep2
             *      Cache:
             *      term --> rep1, rep2
             *
             * for non-replace synonym types (term, query), original term and alternatives form the complete alternative set
             * and secondly, original term and synonyms form each others alternatives
             * Example
             *       termA as synonym as syn1 and syn2
             *       All synonym alternatives: termA, syn1, syn2
             *       Cache:
             *          termA --> termA, syn1, syn2
             *          syn1 --> termA, syn1, syn2
             *          syn2 --> termA, syn1, syn2
             */
            if (this.augmentationDao != null) {
                List<Synonym> allSynonyms = this.augmentationDao.getSynonyms();

                if (allSynonyms != null && allSynonyms.size() > 0) {
                    TermAlternativesWrapper termAlternativesWrapper = new TermAlternativesWrapper();
                    Set<String> currItemSynonyms;
                    Set<String> augmentations;

                    for (Synonym currSynonym : allSynonyms) {
                        if (!StringUtils.isBlank(currSynonym.getSynonyms())) {

                            augmentations = new HashSet<String>();
                            currItemSynonyms = Sets.newHashSet(currSynonym.getSynonyms().split(","));

                            if (!currSynonym.getSynType().equals(Synonym.Type.replace)) {
                                currItemSynonyms.add(currSynonym.getTerm());;
                            }

                            for (String synonym : currItemSynonyms) {
                                synonym = synonym.replaceAll("\\s+", " ").trim();
                                // in case of replace, mostly we have query to query replace and putting an extra braces is not what we want.
                                if (synonym.contains(" ") && !currSynonym.getSynType().equals(Synonym.Type.replace)) {
                                    augmentations.add("(" + synonym + ")");
                                } else {
                                    augmentations.add(synonym);
                                }
                            }

                            if (currSynonym.getSynType().equals(Synonym.Type.replace)) {//query to query alternative
                                addQueryAlternatives(termAlternativesWrapper, currSynonym.getTerm(), augmentations,
                                    AugmentationConstants.CONTEXT_DEFAULT, AugmentAlternative.Type.Synonym.name(), 0);
                            }else {//term to term alternatives
                                for (String singleSynonym : currItemSynonyms) {
                                    //All alternatives are added to each of the synonym
                                    addTermAlternatives(termAlternativesWrapper, singleSynonym, augmentations, AugmentationConstants.CONTEXT_DEFAULT,
                                        AugmentAlternative.Type.Synonym.name(), 0);
                                }
                            }
                        }
                    }
                    return termAlternativesWrapper;
                }
            }
            return null;
        }


        @VisibleForTesting
        TermAlternativesWrapper loadCompoundWords(boolean useNewCompounds) {

            /**
             * both unigram and bigram are considered as alternative
             * Depending on which one is correct (unigram or bigram) the other one is considered as term to correct.
             *      eg if unigram is correct, bigram is considered as term to be corrected and used as cache key
             */

            List<BiCompound> biCompoundList = null;
            if (useNewCompounds) {
                EntityMeta compoundTableMeta = this.augmentationDao.getEntityMeta("bi_compound");
                log.info("Fetching data from compound table {} having base name: {}", compoundTableMeta.getLatestEntityTable(),
                    compoundTableMeta.getBaseTable());
                biCompoundList = this.rawQueriesDao.getAugmentationCompounds(compoundTableMeta.getLatestEntityTable());
            }
            else{
                biCompoundList = this.augmentationDao.getOldCompounds();
            }

            if (biCompoundList != null && biCompoundList.size() > 0) {
                TermAlternativesWrapper termAlternativesWrapper = new TermAlternativesWrapper();

                for (BiCompound currBiCompound : biCompoundList) {

                    String unigram = currBiCompound.getUnigram();
                    String bigram = currBiCompound.getBigram();
                    String correct = currBiCompound.getCorrect();

                    float conf = getConfidenceForCompoundWordEntry(correct, currBiCompound.getUnigramPHits(),
                        currBiCompound.getUnigramSHits(), currBiCompound.getBigramPHits(), currBiCompound.getBigramSHits());

                    Set<String> augmentations = new HashSet<String>(Arrays.asList(unigram, "(" + bigram + ")"));
                    if ("both".equalsIgnoreCase(correct)) {
                        if (areGramsValid(unigram, bigram)) {
                            addTermAlternatives(termAlternativesWrapper, bigram, augmentations, AugmentationConstants.CONTEXT_DEFAULT,
                                AugmentAlternative.Type.CompundWord.name(), conf);
                        }
                        addTermAlternatives(termAlternativesWrapper, unigram, augmentations, AugmentationConstants.CONTEXT_DEFAULT,
                            AugmentAlternative.Type.CompundWord.name(), conf);
                    }
                    else if ("unigram".equals(correct)) {
                        if (areGramsValid(unigram, bigram)) {
                            addTermAlternatives(termAlternativesWrapper, bigram, augmentations, AugmentationConstants.CONTEXT_DEFAULT,
                                AugmentAlternative.Type.CompundWord.name(), conf);
                        }
                    }
                    else if ("bigram".equals(correct)) {
                        addTermAlternatives(termAlternativesWrapper, unigram, augmentations, AugmentationConstants.CONTEXT_DEFAULT,
                            AugmentAlternative.Type.CompundWord.name(), conf);
                    }
                }
                return termAlternativesWrapper;
            }
            return null;
        }

        @VisibleForTesting
        Set<String> loadNegatives(){
            try {
                return this.augmentationDao.getNegatives();
            }
            catch(Exception e){
                log.error("Exception while getting negative list", e);
            }
            return null;
        }


        @VisibleForTesting
        TermAlternativesWrapper loadSpellVariations(){
            try {
                List<SpellCorrection> allSpellCorrections = new ArrayList<>();

                //Load new spellings
                EntityMeta spellingsTableMeta = this.augmentationDao.getEntityMeta("spellcheck_new");
                log.info("Fetching data from spelling table {} having base name: {}", spellingsTableMeta.getLatestEntityTable(),
                    spellingsTableMeta.getBaseTable());
                List<SpellCorrection> newSpellCorrections = this.rawQueriesDao.getAugmentationSpellCorrections(spellingsTableMeta.getLatestEntityTable());

                //Load old spellings
                List<SpellCorrection> oldSpellCorrections = this.augmentationDao.getSpellCorrectionsLowConf();

                if (newSpellCorrections != null && newSpellCorrections.size() > 0) {
                    allSpellCorrections.addAll(newSpellCorrections);
                }
                if (oldSpellCorrections != null && oldSpellCorrections.size() > 0) {
                    allSpellCorrections.addAll(oldSpellCorrections);
                }

                if (allSpellCorrections.size() > 0) {
                    TermAlternativesWrapper termAlternativesWrapper = new TermAlternativesWrapper();
                    Set<String> augmentations;
                    for (SpellCorrection correction : allSpellCorrections) {
                        augmentations = this.objectMapper.readValue(correction.getCorrectSpelling(), new TypeReference<Set<String>>() {});
                        augmentations.add(correction.getIncorrectSpelling());
                        //add alternatives for incorrect spelling
                        addTermAlternatives(termAlternativesWrapper, correction.getIncorrectSpelling(),
                            augmentations, AugmentationConstants.CONTEXT_DEFAULT, AugmentAlternative.Type.SpellVariation.name(), 0);
                    }
                    return termAlternativesWrapper;
                }

            }
            catch(Exception ex){
                log.error("Exception in loading spelling variations", ex);
            }
            return null;
        }


        @VisibleForTesting
        TermAlternativesWrapper loadAugmentationExperiments(){
            try {
                List<AugmentationExperiment> augmentationExperiments = this.augmentationDao.getAugmentationExperiements();

                Set<String> correctSet;
                if(augmentationExperiments != null && augmentationExperiments.size() > 0) {
                    TermAlternativesWrapper termAlternativesWrapper = new TermAlternativesWrapper();
                    for (AugmentationExperiment experiment : augmentationExperiments) {
                        float conf = 0f;
                        try {
                            conf = Float.parseFloat(experiment.getContext());
                        } catch (Exception e) {
                            //swallow
                        }

                        String type = experiment.getType();
                        String correct = experiment.getCorrectQuery();
                        String incorrect = experiment.getIncorrectQuery();
                        String sourceName = experiment.getSource();
                        correctSet = new HashSet<String>();

                        if (type.equalsIgnoreCase(AugmentationExperiment.Type.replace.name())
                            || type.equalsIgnoreCase(AugmentationExperiment.Type.replaceNoShow.name())) {
                            correctSet.add(correct);
                        } else {
                            // if the type is not replace, then we can have query synonyms or word synonyms.
                            // Note we use replace for query->query (word ->word replace is not supported)
                            // Infact query-->query OR makes no sense, it should rather always be query->query replace.
                            // for space separated words we need to add brackets

                            correctSet.add(correct.contains(" ") ? "(" + correct + ")" : correct);
                            correctSet.add(incorrect.contains(" ") ? "(" + incorrect + ")" : incorrect);  //add self
                        }

                        //Note: source is used as context
                        if (type.equalsIgnoreCase(AugmentationExperiment.Type.term.name())) {
                            addTermAlternatives(termAlternativesWrapper, incorrect, correctSet, sourceName, type, conf);
                        } else {
                            addQueryAlternatives(termAlternativesWrapper, incorrect, correctSet, sourceName, type, conf);
                        }
                    }
                    return termAlternativesWrapper;
                }
            }
            catch(Exception ex){
                log.error("Exception while loading augmentation experiments", ex);
            }
            return null;
        }


        private void addTermAlternatives(TermAlternativesWrapper termAlternativesWrapper, String original, Set<String> augmentationSet,
                                         String context, String augType, float confidence){
            Set<AugmentAlternative> augmentAlternatives = createAugmentationEntries(original,
                augmentationSet, context, augType, confidence);
            //add to term to term map
            termAlternativesWrapper.addTermAlternatives(original, augmentAlternatives);
        }

        private void addQueryAlternatives(TermAlternativesWrapper termAlternativesWrapper, String original, Set<String> augmentationSet,
                                          String context, String augType, float confidence){
            Set<AugmentAlternative> augmentAlternatives = createAugmentationEntries(original,
                augmentationSet, context, augType, confidence);
            //add to query to query map
            termAlternativesWrapper.addQueryAlternatives(original, augmentAlternatives);
        }


        private boolean areGramsValid(String unigram, String bigram) {
            if (!StringUtils.isBlank(unigram) && !StringUtils.isBlank(bigram)){
                Set<String> bigramTokens = Lists.newArrayList(StringUtils.split(bigram, ",")).stream().map(String::trim).collect(Collectors.toSet());
                return !(stopWordsSet.containsAll(bigramTokens));
            }
            return false;
        }

        //Copied as-is from previous version of semantic
        private float getConfidenceForCompoundWordEntry(String correct, float uni_phits, float uni_shits, float bi_phits,
                                                        float bi_shits) {

            float conf = 0;

            float correct_phits = 0.0f;
            float correct_shits = 0.0f;
            float incorrect_phits = 0.0f;
            float incorrect_shits = 0.0f;

            if (correct.equalsIgnoreCase("unigram")) {
                correct_phits = uni_phits;
                correct_shits = uni_shits;
                incorrect_phits = bi_phits;
                incorrect_shits = bi_shits;
            }
            else if (correct.equalsIgnoreCase("bigram")) {
                correct_phits = bi_phits;
                correct_shits = bi_shits;
                incorrect_phits = uni_phits;
                incorrect_shits = uni_shits;
            }
            else { //if its unspecified or both are correct.
                return 0;
            }

            // the incorrect guys occurs with some frequency, the correct guy has decent phits.
            // and the correct is order of magnitude better than incorrect.
            if (incorrect_shits > 5 && correct_phits > 50 &&
                correct_shits > 5*incorrect_shits &&
                correct_phits > 5*incorrect_phits) {
                conf = correct_phits/ (incorrect_phits < 1 ? 1: incorrect_phits);
            }

            return conf;
        }


        private Set<AugmentAlternative> createAugmentationEntries(String original, Set<String> augmentationSet, String context,
                                                                  String augType, float confidence) {
            if (!StringUtils.isBlank(original) && augmentationSet != null && augmentationSet.size() > 0
                && context != null && augType != null){
                Set<AugmentAlternative> augmentAlternatives = new HashSet<>();
                for (String currAug : augmentationSet) {
                    augmentAlternatives.add(new AugmentAlternative(original, currAug, context, augType, confidence));
                }
                return augmentAlternatives;
            }
            return null;
        }
    }

    private static String convertToKey(String text) {
        return StringUtils.lowerCase(StringUtils.trimToEmpty(text));
    }
}
