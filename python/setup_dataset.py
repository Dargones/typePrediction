#!/usr/bin/env python
import argparse
from collections import Counter
import json
import pickle
from sklearn.model_selection import train_test_split
import sys
import tensorflow as tf
from tqdm.auto import tqdm
import warnings

MAX_DOC = 500  # a cap (in characters) on the comment size
DELIMITER = "^"  # delimiter to place between param name and the doc string


def prepare_dataset(data, record_dp_func, use_tqdm=False):
    """
    Get the dataset in the original .json format and convert it to the format required by the models
    :param record_dp_func:   function with which to record a datapoint (varies for names/comms/nc)
    :param data:        the data in the same format in which it is stored on disk (.json files)
    :param use_tqdm:    whether to display a tqdm progress_bar
    :return:            a tuple of two elements:
                        1) dataset: a list of dicts in the following format:
                        {'input': 'INPUT_DATA', 'output': 'TYPE', 'program': 'PROGRAM_NAME'}
                        2) prog_type_dict: dict mapping a program name to a set of types observed
                        in the corresponding program
    """
    dataset = []
    prog_type_dict = {}

    data_keys = data.keys()
    if use_tqdm:
        data_keys = tqdm(data_keys)

    for program in data_keys:
        for clazz in data[program].values():
            for method_name, method in clazz.items():
                if method.get('return', None):  # if the given method returns a value
                    record_dp_func(dataset, prog_type_dict, program, method_name, method['return'])
                for param_name, param_hash in method['params'].items():  # for each parameter
                    record_dp_func(dataset, prog_type_dict, program, param_name, param_hash)

    return dataset, prog_type_dict


def record_names_datapoint(dataset, prog_type_dict, program, param_name, param_hash):
    """
    Record a parameter or a return value of a method in the names dataset.
    This function can be passed as an argument to prepare_dataset()
    :param dataset:         see prepare_dataset()
    :param prog_type_dict:  see prepare_dataset()
    :param program:         the name of the program in which the method appears
    :param param_name:      name of the parameter (method name for the return value)
    :param param_hash:      dict containing information about the parameter as recorded in the .json
    """
    if param_hash.get('type', 'void') == 'void':
        return
    param_type = param_hash['type'].lower()
    t = {'input': param_name, 'output': param_type, 'program': program}
    dataset.append(t)
    prog_type_dict.setdefault(program, set()).add(param_type)


def record_comments_datapoint(dataset, prog_type_dict, program, param_name, param_hash,
                              max_doc=MAX_DOC):
    """
    Record a parameter or a return value of a method in the comments dataset.
    See record_names_datapoint() for parameter documentation.
    This function can be passed as an argument to prepare_dataset().
    :param max_doc: a cap (in characters) on the comment size
    """
    if param_hash.get('type', 'void') == 'void' or 'doc' not in param_hash:
        return
    param_type = param_hash['type'].lower()
    param_doc = param_hash['doc'].lower()
    t = {'input': param_doc[:max_doc], 'output': param_type, 'program': program}
    dataset.append(t)
    prog_type_dict.setdefault(program, set()).add(param_type)


def record_nc_datapoint(dataset, prog_type_dict, program, param_name, param_hash, max_doc=MAX_DOC,
                        delimiter=DELIMITER):
    """
    Record a parameter or a return value of a method in the comments dataset.
    See record_names_datapoint() for parameter documentation.
    This function can be passed as an argument to prepare_dataset().
    :param max_doc:         a cap (in characters) on the comment size
    :param delimiter:       delimiter to place between param name and the oc string
    """
    if param_hash.get('type', 'void') == 'void' or 'doc' not in param_hash:
        return
    param_type = param_hash['type'].lower()
    param_doc = param_hash['doc'].lower()
    t = {'input': param_name + delimiter + param_doc[:max_doc],
         'output': param_type,
         'program': program}
    dataset.append(t)
    prog_type_dict.setdefault(program, set()).add(param_type)


def choose_top_labels(dataset, prog_type_dict, label_choice, label_num, min_prognum_labels):
    """
    :param dataset:         See documentation for return value of prepare_dataset()
    :param prog_type_dict:  See documentation for return value of prepare_dataset()
    :param label_choice:    The way to select the labels. Can either be "PROG", "TOP", or "ALL"
                            "TOP" - select top most frequent labels in the dataset
                            "PROG" - select labels found in at least MIN_PROGNUM_LABELS programs
                            "ALL" - select all labels
    :param label_num:       When LABEL_CHOICE is "TOP",
                            this is the number of distinct labels in the dataset
    :param min_prognum_labels:  When LABEL_CHOICE is "PROG", this is the minimum number of programs
                                a type should occur in for it to be used as a label.
    :return:                list of labels
    """
    if label_choice == "TOP":
        return [x[0] for x in Counter([x['output'] for x in dataset]).most_common(label_num)]
    elif label_choice == "PROG":
        # first count how many programs each type appears in
        type_progcount = {}
        for typ in Counter([x['output'] for x in dataset]).keys():
            prog_count = 0
            for prog_typs in prog_type_dict.values():
                if typ in prog_typs:
                    prog_count += 1
            type_progcount[typ] = prog_count
        return [key for key,value in type_progcount.items() if value >= min_prognum_labels]
    elif label_choice == "ALL":
        return [x['output'] for x in dataset]
    else:
        print('unexpected value of label_choice: {}'.format(label_choice))
        raise ValueError


def create_labels(dataset, prog_type_dict, other_label, **kwargs):
    """
    Create label indices, and a dictionary to map back from indices to label names.
    :param dataset:         See documentation for return value of prepare_dataset()
    :param prog_type_dict:  See documentation for return value of prepare_dataset()
    :param other_label:     if OTHER_LABEL is "", only keep in the dataset the labels selected
                            with choose_top_labels(). Otherwise, if OTHER_LABEL is a string,
                            replace other labels with that string
    :param kwargs:          Parameters for choose_top_labels()
    :return:                (label to index dictionary, index to label dictionary)
    """
    top_labels = choose_top_labels(dataset, prog_type_dict, **kwargs)

    label_to_idx = {x: top_labels.index(x) for x in top_labels}
    idx_to_label = {v: k for k, v in label_to_idx.items()}
    if other_label != "":
        label_to_idx[other_label] = len(top_labels) + 1
        idx_to_label[len(top_labels)] = other_label
    return label_to_idx, idx_to_label

    
def create_tokenizer(dataset):
    """
    'Create' a character tokenizer. In essence, count all distinct characters in teh training set
    and assign an index to every one of these
    :param dataset: a dataset returned by prepare_dataset
    :return:        a keras tokenizer
    """
    lang_tokenizer = tf.keras.preprocessing.text.Tokenizer(char_level=True)
    lang_tokenizer.fit_on_texts([x['input'] for x in dataset])
    return lang_tokenizer


def train_test_dev_split(programs, test_set_progs_file=None, test_size=0.05, develop_size=0.1):
    """
    Split the list of programs in the dataset into training, testing, and development sets.
    :param programs:        list of programs in the dataset
    :param test_set_progs_file:  predefined list of programs that go into testing set
    :param test_size:       fraction of the original dataset to go into the testing set
                            This parameter is only used if test_set_progs == None
    :param develop_size:    fraction of non-testing data that is the development set
    :return:                tuple of three lists of program names (training, testing, dev. resp.)
    """
    # train-test split is either predefined or random
    if test_set_progs_file:
        programs_test = [x.strip() for x in test_set_progs_file.readlines()]
        programs_train = [x for x in programs if x not in programs_test]
    else:
        programs_train, programs_test = train_test_split(programs, test_size=test_size)
    # random train-development split
    programs_train, programs_dev = train_test_split(programs_train, test_size=develop_size)
    return programs_train, programs_test, programs_dev


def vectorize_and_filter(dataset, lang_tokenizer, label_to_idx, other_label):
    """
    Use a tokenizer to vectorize a dataset returned by prepare_dataset(). If other_label is None,
    remove from the dataset all the data-points the labels for which are not within label_to_idx.
    :param dataset:         a dataset as returned by prepare_dataset()
    :param lang_tokenizer:  a tokenizer as returned by create_tokenizer()
    :param label_to_idx:    label to idx dictionary as returned by create_labels
    :param other_label:     if OTHER_LABEL is "", only keep in the dataset the labels selected
                            with choose_top_labels(). Otherwise, if OTHER_LABEL is a string,
                            replace other labels with that string
    :return:
    """
    input_data = []
    output_data = []

    for sample in dataset:
        if label_to_idx.get(sample['output'], -1) > -1:
            input_data.append(lang_tokenizer.texts_to_sequences(sample['input']))
            if [] in input_data[-1]:
                warnings.warn("Tokenizer failed to tokenize a character!")
                input_data[-1] = [x for x in input_data[-1] if len(x) > 0]
            output_data.append(label_to_idx[sample['output']])
        elif other_label != "":
            input_data.append(lang_tokenizer.texts_to_sequences(sample['input']))
            output_data.append(label_to_idx[other_label])

    in_data = tf.keras.preprocessing.sequence.pad_sequences(input_data).squeeze()
    return in_data, output_data


def pickle_dump(data, file):
    """
    Shorthand for safe pickle.dump()
    :param data: data to be dumped
    :param file: the file wo which to dumpt teh data
    :return:
    """
    with open(file, 'wb') as f:
        pickle.dump(data, f, pickle.HIGHEST_PROTOCOL)


def parse_arguments():
    """
    Read the arguments from command line and return them for later use
    :return:
    """
    p = argparse.ArgumentParser(description='Prepare the dataset for use by neural models.')
    p.add_argument("json_file", type=argparse.FileType('r'), help="json file with all the data")
    p.add_argument("prefix", type=str, help="prefix for all the generated files")
    p.add_argument("data_type", type=str, choices=["names", "commens", "nc"],
                   default="nc", help="type of the information recorded in the dataset")
    p.add_argument("labels", type=str, choices=["PROG", "ALL", "TOP"],
                   default="PROG", help="method by which to choose the labels for the dataset")
    p.add_argument("-other_label", type=str, required=False, default="",
                   help="label to use instead of all infrequent labels. "
                        "This can be left blank to ignore infrequent labels altogether")
    p.add_argument("-label_num", type=int, default=100, required=False,
                   help="Number of most frequent labels to keep. Works with label_choice=TOP")
    p.add_argument("-min_prog_labels", type=int, default=5, required=False,
                   help="Minimal number of programs a label has to appear in for it to be included "
                        "in the dataset. Works with label_choice=PROG")
    p.add_argument("-test_prog_list", type=argparse.FileType('r'), default=None, required=False,
                   help="file with the list of programs in the test set (optional)")

    return p.parse_args(sys.argv[1:])


if __name__ == "__main__":
    args = parse_arguments()
    print(args)

    # choose the correct function for constructing the dataset
    record_dp_func_dict = {"names": record_names_datapoint, "comments": record_comments_datapoint,
                           "nc": record_nc_datapoint}
    record_dp_func = record_dp_func_dict[args.data_type]

    # load the dataset, split it, and convert it into input-label format
    data = json.load(args.json_file)
    programs_train, programs_test, programs_dev = train_test_dev_split(list(data.keys()),
                                                                       args.test_prog_list)

    data_train, prog_type_dict = prepare_dataset({x: data[x] for x in programs_train},
                                                 record_dp_func,
                                                 use_tqdm=True)
    data_test, _ = prepare_dataset({x: data[x] for x in programs_test}, record_dp_func, True)
    data_dev, _ = prepare_dataset({x: data[x] for x in programs_dev}, record_dp_func, True)

    # use the training set to fir the tokenizer and construct the label dictionary
    label_to_idx, idx_to_label = create_labels(data_train, prog_type_dict, args.other_label,
                                               label_choice=args.labels,
                                               label_num=args.label_num,
                                               min_prognum_labels=args.min_prog_labels)
    tokenizer = create_tokenizer(data_train)

    # vectorize the datasets
    final_train_set = vectorize_and_filter(data_train, tokenizer, label_to_idx, args.other_label)
    final_dev_set = vectorize_and_filter(data_dev, tokenizer, label_to_idx, args.other_label)
    final_test_set = vectorize_and_filter(data_test, tokenizer, label_to_idx, args.other_label)

    # save everything
    pickle_dump(final_train_set, args.prefix + "train_set.pkl")
    pickle_dump(final_test_set, args.prefix + "test_set.pkl")
    pickle_dump(final_dev_set, args.prefix + "dev_set.pkl")
    pickle_dump(tokenizer, args.prefix + "tokenizer.pkl")
    pickle_dump((label_to_idx, idx_to_label), args.prefix + "label_dict.pkl")