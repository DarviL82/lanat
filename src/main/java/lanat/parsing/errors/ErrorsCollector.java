package lanat.parsing.errors;

import lanat.Command;
import lanat.parsing.Token;
import org.jetbrains.annotations.NotNull;
import utils.Pair;
import utils.UtlReflection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import java.util.function.Supplier;

/**
 * Handles all errors generated by the parser and tokenizer.
 */
public class ErrorsCollector {
	/** The full list of tokens that were passed to the main parser. */
	private final @NotNull List<@NotNull Token> fullTokenList;
	/** The full input string that was passed to the main parser. */
	private final @NotNull String fullInput;
	/** The errors that were collected. */
	private final @NotNull Hashtable<Command, List<Error<?>>> errors = new Hashtable<>();

	/** The current error formatters for tokenization errors when being handled. */
	private ErrorFormatter tokenizeFormatter, parseFormatter;

	/**
	 * Instantiates a new errors collector.
	 * @param fullTokenList the full list of tokens that were passed to the main parser
	 * @param fullInputString the full input string that was passed to the main parser
	 */
	public ErrorsCollector(@NotNull List<@NotNull Token> fullTokenList, @NotNull String fullInputString) {
		this.fullTokenList = fullTokenList;
		this.fullInput = fullInputString;
	}

	/**
	 * Collects all the errors from the given command.
	 * <p>
	 * Gets the errors from (in order):
	 * <ol>
	 * <li>Tokenizer</li>
	 * <li>Parser</li>
	 * <li>Arguments</li>
	 * <li>Custom errors added to the Command</li>
	 * </ol>
	 * @param command the command to collect the errors from
	 */
	public void collect(@NotNull Command command) {
		this.errors.put(command, new ArrayList<>() {{
			this.addAll(command.getTokenizer().getErrorsUnderDisplayLevel());
			this.addAll(command.getParser().getErrorsUnderDisplayLevel());
			command.getArguments().forEach(arg -> this.addAll(arg.getErrorsUnderDisplayLevel()));
			this.addAll(command.getErrorsUnderDisplayLevel());
		}});
	}


	/**
	 * Handles all the errors that were collected. This method will return a list of formatted error messages.
	 * <br>
	 * The errors are sorted by their absolute index in the input.
	 * @return a list of formatted error messages
	 */
	public @NotNull List<@NotNull String> handleErrors() {
		// store pairs of error messages and their absolute input value index
		final var errorMessages = new ArrayList<Pair<String, Integer>>();

		// iterate for each command
		for (var pair : this.errors.entrySet()) {
			final var command = pair.getKey();
			final var errors = pair.getValue();

			// iterate for each error in each command
			for (var error : errors) {
				// create a new error formatting context for each error
				final var errorFormattingCtx = new ErrorFormattingContext();

				// Will hold the formatter for the current error
				ErrorFormatter formatter = null;

				// for each type of error, get the appropriate formatter and call the appropriate handle method.
				if (error instanceof Error.TokenizeError tokenizeError) {
					formatter = this.getTokenizeFormatter(command);
					tokenizeError.handle(errorFormattingCtx, (TokenizeErrorContext)formatter.getCurrentErrorContext());
				} else if (error instanceof Error.ParseError parseError) {
					formatter = this.getParseFormatter(command);
					parseError.handle(errorFormattingCtx, (ParseErrorContext)formatter.getCurrentErrorContext());
				}

				assert formatter != null; // impossible because Error is sealed

				errorMessages.add(new Pair<>(
					formatter.generateInternal(error, errorFormattingCtx), // generate the error message
					formatter.getHighlightOptions()
						.map(v -> v.range().start())
						.orElse(Integer.MAX_VALUE) // if there are no highlight options, put the error at the end
				));
			}
		}

		return errorMessages.stream()
			.sorted(Comparator.comparingInt(Pair::second)) // sort by the absolute index
			.map(Pair::first) // get the error message
			.toList();
	}

	/**
	 * Returns the tokenize error formatter for the given command. The formatter is shared between all errors in the
	 * same command.
	 * @param cmd the command to get the formatter for
	 * @return the tokenize error formatter for the given command
	 */
	private @NotNull ErrorFormatter getTokenizeFormatter(@NotNull Command cmd) {
		if (this.tokenizeFormatter == null || this.tokenizeFormatter.getCurrentErrorContext().getCommand() != cmd)
			this.tokenizeFormatter = getFormatter(() -> new TokenizeErrorContext(cmd, this.fullInput));

		return this.tokenizeFormatter;
	}

	/**
	 * Returns the parse error formatter for the given command. The formatter is shared between all errors in the
	 * same command.
	 * @param cmd the command to get the formatter for
	 * @return the parse error formatter for the given command
	 */
	private @NotNull ErrorFormatter getParseFormatter(@NotNull Command cmd) {
		if (this.parseFormatter == null || this.parseFormatter.getCurrentErrorContext().getCommand() != cmd)
			this.parseFormatter = getFormatter(() -> new ParseErrorContext(cmd, this.fullTokenList));

		return this.parseFormatter;
	}

	/**
	 * Instantiates the error formatter in {@link ErrorFormatter#errorFormatterClass} for the given error context.
	 * @param ctx the error context to instantiate the formatter for
	 * @return the error formatter
	 */
	private static @NotNull ErrorFormatter getFormatter(@NotNull Supplier<? extends ErrorContext> ctx) {
		return UtlReflection.instantiate(
			ErrorFormatter.errorFormatterClass,
			List.of(ErrorContext.class),
			List.of(ctx.get())
		);
	}
}